package com.linkedin.datahub.graphql.resolvers.search;

import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.bindArgument;
import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.getQueryContext;
import static com.linkedin.datahub.graphql.resolvers.search.SearchUtils.*;
import static com.linkedin.datahub.graphql.resolvers.search.SearchUtils.getEntityNames;

import com.google.common.collect.ImmutableList;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.concurrency.GraphQLConcurrencyUtils;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.SearchAcrossEntitiesInput;
import com.linkedin.datahub.graphql.generated.SearchResults;
import com.linkedin.datahub.graphql.resolvers.ResolverUtils;
import com.linkedin.datahub.graphql.types.mappers.UrnSearchResultsMapper;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.metadata.search.SearchResult;
import com.linkedin.metadata.search.semantic.SemanticEntitySearch;
import com.linkedin.metadata.service.ViewService;
import com.linkedin.metadata.utils.CriterionUtils;
import com.linkedin.view.DataHubViewInfo;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/** Resolver responsible for resolving 'searchAcrossEntities' field of the Query type */
@Slf4j
public class SearchAcrossEntitiesResolver implements DataFetcher<CompletableFuture<SearchResults>> {

  private static final int DEFAULT_START = 0;
  private static final int DEFAULT_COUNT = 10;

  private final EntityClient _entityClient;
  private final ViewService _viewService;

  /** Optional semantic search service. Non-null only when KNN search is enabled. */
  @Nullable private final SemanticEntitySearch _semanticEntitySearch;

  /** When true, KNN results are fetched in parallel and appended after text results. */
  private final boolean _knnSearchEnabled;

  public SearchAcrossEntitiesResolver(EntityClient entityClient, ViewService viewService) {
    this(entityClient, viewService, null, false);
  }

  public SearchAcrossEntitiesResolver(
      EntityClient entityClient,
      ViewService viewService,
      @Nullable SemanticEntitySearch semanticEntitySearch,
      boolean knnSearchEnabled) {
    this._entityClient = entityClient;
    this._viewService = viewService;
    this._semanticEntitySearch = semanticEntitySearch;
    this._knnSearchEnabled = knnSearchEnabled;
  }

  @Override
  public CompletableFuture<SearchResults> get(DataFetchingEnvironment environment) {
    final QueryContext context = getQueryContext(environment);
    final SearchAcrossEntitiesInput input =
        bindArgument(environment.getArgument("input"), SearchAcrossEntitiesInput.class);

    final List<String> entityNames = getEntityNames(input.getTypes());

    // escape forward slash since it is a reserved character in Elasticsearch
    final String sanitizedQuery = ResolverUtils.escapeForwardSlash(input.getQuery());

    final int start = input.getStart() != null ? input.getStart() : DEFAULT_START;
    final int count = input.getCount() != null ? input.getCount() : DEFAULT_COUNT;

    return GraphQLConcurrencyUtils.supplyAsync(
        () -> {
          final DataHubViewInfo maybeResolvedView =
              (input.getViewUrn() != null)
                  ? resolveView(
                      context.getOperationContext(),
                      _viewService,
                      UrnUtils.getUrn(input.getViewUrn()))
                  : null;

          final Filter baseFilter =
              ResolverUtils.buildFilter(input.getFilters(), input.getOrFilters());

          SearchFlags searchFlags = mapInputFlags(context, input.getSearchFlags());
          List<SortCriterion> sortCriteria = SearchUtils.getSortCriteria(input.getSortInput());

          try {
            log.debug(
                "Executing search for multiple entities: entity types {}, query {}, filters: {}, start: {}, count: {}",
                input.getTypes(),
                input.getQuery(),
                input.getOrFilters(),
                start,
                count);

            List<String> finalEntities =
                maybeResolvedView != null
                    ? SearchUtils.intersectEntityTypes(
                        entityNames, maybeResolvedView.getDefinition().getEntityTypes())
                    : entityNames;
            if (finalEntities.size() == 0) {
              return SearchUtils.createEmptySearchResults(start, count);
            }

            // Build the final filter, combining view filter and entity-specific defaults
            Filter combinedFilter =
                maybeResolvedView != null
                    ? SearchUtils.combineFilters(
                        baseFilter, maybeResolvedView.getDefinition().getFilter())
                    : baseFilter;

            // Add default entity filters that should be applied to all queries
            combinedFilter =
                DefaultEntityFiltersUtil.addDefaultEntityFilters(
                    combinedFilter, finalEntities, true);

            boolean shouldIncludeStructuredPropertyFacets =
                input.getSearchFlags() != null
                        && input.getSearchFlags().getIncludeStructuredPropertyFacets() != null
                    ? input.getSearchFlags().getIncludeStructuredPropertyFacets()
                    : false;
            List<String> structuredPropertyFacets =
                shouldIncludeStructuredPropertyFacets ? getStructuredPropertyFacets(context) : null;

            // Execute text search
            SearchResult searchResult =
                _entityClient.searchAcrossEntities(
                    context.getOperationContext().withSearchFlags(flags -> searchFlags),
                    finalEntities,
                    sanitizedQuery,
                    combinedFilter,
                    start,
                    count,
                    sortCriteria,
                    structuredPropertyFacets);

            // Cleanse aggregations to remove hidden/default filter fields
            searchResult =
                DefaultEntityFiltersUtil.removeDefaultFilterFieldsFromAggregations(searchResult);

            // When KNN search is enabled, fetch semantic results in parallel and append
            // entities that text search did not already return.
            if (_knnSearchEnabled && _semanticEntitySearch != null) {
              searchResult =
                  mergeWithKnnResults(
                      context,
                      searchResult,
                      finalEntities,
                      sanitizedQuery,
                      combinedFilter,
                      start,
                      count);
            }

            return UrnSearchResultsMapper.map(context, searchResult);
          } catch (Exception e) {
            log.error(
                "Failed to execute search for multiple entities: entity types {}, query {}, filters: {}, start: {}, count: {}",
                input.getTypes(),
                input.getQuery(),
                input.getOrFilters(),
                start,
                count);
            throw new RuntimeException(
                "Failed to execute search: "
                    + String.format(
                        "entity types %s, query %s, filters: %s, start: %s, count: %s",
                        input.getTypes(), input.getQuery(), input.getOrFilters(), start, count),
                e);
          }
        },
        this.getClass().getSimpleName(),
        "get");
  }

  private List<String> getStructuredPropertyFacets(final QueryContext context) {
    try {
      SearchFlags searchFlags = new SearchFlags().setSkipCache(true);
      SearchResult result =
          _entityClient.searchAcrossEntities(
              context.getOperationContext().withSearchFlags(flags -> searchFlags),
              getEntityNames(ImmutableList.of(EntityType.STRUCTURED_PROPERTY)),
              "*",
              createStructuredPropertyFilter(),
              0,
              100,
              Collections.emptyList());
      return result.getEntities().stream()
          .map(entity -> String.format("structuredProperties.%s", entity.getEntity().getId()))
          .collect(Collectors.toList());
    } catch (Exception e) {
      log.error("Failed to get structured property facets to filter on", e);
      return Collections.emptyList();
    }
  }

  /**
   * Executes KNN semantic search and merges the results with the existing text search result.
   *
   * <p>Strategy: text-search entities come first (order preserved). KNN entities whose URN did not
   * appear in the text result are appended at the end. The total count reflects the union size, and
   * the original aggregation metadata is preserved so facets remain usable.
   *
   * <p>KNN errors are swallowed so a semantic search failure never breaks the main text result.
   */
  private SearchResult mergeWithKnnResults(
      QueryContext context,
      SearchResult textResult,
      List<String> entityNames,
      String query,
      Filter filter,
      int start,
      int count) {
    try {
      SearchResult knnResult =
          _semanticEntitySearch.search(
              context.getOperationContext(), entityNames, query, filter, null, 0, count);

      if (knnResult == null || knnResult.getEntities().isEmpty()) {
        return textResult;
      }

      // Build URN-keyed map of text results to detect duplicates in O(1)
      Map<String, SearchEntity> textEntitiesByUrn = new LinkedHashMap<>();
      for (SearchEntity entity : textResult.getEntities()) {
        textEntitiesByUrn.put(entity.getEntity().toString(), entity);
      }

      // Collect KNN-only entities (not already in text results)
      List<SearchEntity> knnOnlyEntities = new java.util.ArrayList<>();
      for (SearchEntity knnEntity : knnResult.getEntities()) {
        if (!textEntitiesByUrn.containsKey(knnEntity.getEntity().toString())) {
          knnOnlyEntities.add(knnEntity);
        }
      }

      if (knnOnlyEntities.isEmpty()) {
        return textResult;
      }

      // Merge: text-result entities first, then KNN-only additions
      SearchEntityArray merged = new SearchEntityArray(textResult.getEntities());
      merged.addAll(knnOnlyEntities);

      log.debug(
          "KNN search added {} new entities to text search results (total: {})",
          knnOnlyEntities.size(),
          merged.size());

      return textResult.clone().setEntities(merged).setNumEntities(merged.size());
    } catch (Exception e) {
      log.warn("KNN search failed, falling back to text-only results: {}", e.getMessage());
      return textResult;
    }
  }

  private Filter createStructuredPropertyFilter() {
    return new Filter()
        .setOr(
            new ConjunctiveCriterionArray(
                ImmutableList.of(
                    new ConjunctiveCriterion()
                        .setAnd(
                            new CriterionArray(
                                ImmutableList.of(
                                    CriterionUtils.buildCriterion(
                                        "filterStatus", Condition.EQUAL, "ENABLED")))),
                    new ConjunctiveCriterion()
                        .setAnd(
                            new CriterionArray(
                                ImmutableList.of(
                                    CriterionUtils.buildCriterion(
                                        "showInSearchFilters", Condition.EQUAL, "true")))))));
  }
}
