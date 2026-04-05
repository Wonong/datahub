package com.linkedin.metadata.kafka.hook;

import static com.linkedin.metadata.Constants.*;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.EmbeddingChunk;
import com.linkedin.common.EmbeddingChunkArray;
import com.linkedin.common.EmbeddingModelData;
import com.linkedin.common.EmbeddingModelDataMap;
import com.linkedin.common.SemanticContent;
import com.linkedin.common.urn.Urn;
import com.linkedin.dataset.DatasetProperties;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.gms.factory.entityclient.RestliEntityClientFactory;
import com.linkedin.gms.factory.entityregistry.EntityRegistryFactory;
import com.linkedin.gms.factory.search.semantic.EmbeddingProviderFactory;
import com.linkedin.metadata.search.embedding.EmbeddingProvider;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.mxe.GenericAspect;
import com.linkedin.mxe.MetadataChangeLog;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.schema.SchemaMetadata;
import io.datahubproject.metadata.context.OperationContext;
import java.net.URISyntaxException;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

/**
 * Hook that automatically generates embedding vectors for Dataset entities and stores them in the
 * {@code semanticContent} aspect.
 *
 * <p>Triggered when {@code datasetProperties} or {@code schemaMetadata} aspects change. Builds the
 * embedding text from: table name + table description + column names, then calls the configured
 * {@link EmbeddingProvider} and writes the result back as a {@code semanticContent} aspect.
 *
 * <p>This hook is disabled by default ({@code featureFlags.datasetEmbeddingEnabled=false}) to
 * preserve existing behaviour. Enable it after the semantic index has been created (PR 3).
 */
@Slf4j
@Component
@Import({
  EntityRegistryFactory.class,
  RestliEntityClientFactory.class,
  EmbeddingProviderFactory.class
})
public class DatasetEmbeddingHook implements MetadataChangeLogHook {

  public static final String DATASET_EMBEDDING_SYSTEM_ACTOR =
      "urn:li:corpuser:__datahub_system_embedding_hook";

  /** Model key used as the map key inside {@code semanticContent.embeddings}. */
  static final String MODEL_KEY = "kure_v1";

  /** Model version string stored in {@link EmbeddingModelData#setModelVersion}. */
  static final String MODEL_VERSION = "huggingface/dragonkue/kure-v1";

  /**
   * Chunking strategy label. Since we always embed the whole document as a single chunk,
   * "full_document" describes the strategy accurately.
   */
  static final String CHUNKING_STRATEGY = "full_document";

  private static final int MAX_EMBEDDING_TEXT_LENGTH = 2048;

  private final SystemEntityClient systemEntityClient;
  private final EmbeddingProvider embeddingProvider;
  private final boolean isEnabled;
  private OperationContext systemOperationContext;
  @Getter private final String consumerGroupSuffix;

  @Autowired
  public DatasetEmbeddingHook(
      @Nonnull SystemEntityClient systemEntityClient,
      @Nonnull EmbeddingProvider embeddingProvider,
      @Nonnull @Value("${featureFlags.datasetEmbeddingEnabled:false}") Boolean isEnabled,
      @Nonnull @Value("${datasetEmbedding.consumerGroupSuffix:}") String consumerGroupSuffix) {
    this.systemEntityClient = systemEntityClient;
    this.embeddingProvider = embeddingProvider;
    this.isEnabled = isEnabled;
    this.consumerGroupSuffix = consumerGroupSuffix;
  }

  @VisibleForTesting
  public DatasetEmbeddingHook(
      @Nonnull SystemEntityClient systemEntityClient,
      @Nonnull EmbeddingProvider embeddingProvider,
      @Nonnull Boolean isEnabled) {
    this(systemEntityClient, embeddingProvider, isEnabled, "");
  }

  @Override
  public boolean isEnabled() {
    return isEnabled;
  }

  @Override
  public DatasetEmbeddingHook init(@Nonnull OperationContext systemOperationContext) {
    this.systemOperationContext = systemOperationContext;
    return this;
  }

  @Override
  public void invoke(@Nonnull MetadataChangeLog event) throws Exception {
    if (!isEligibleForProcessing(event)) {
      return;
    }

    Urn entityUrn = getUrn(event);
    log.debug(
        "DatasetEmbeddingHook triggered for urn={} aspect={}", entityUrn, event.getAspectName());

    try {
      String embeddingText = buildEmbeddingText(entityUrn);
      if (embeddingText == null || embeddingText.isBlank()) {
        log.debug("Skipping embedding generation for {} — embedding text is empty", entityUrn);
        return;
      }

      // Truncate to avoid exceeding provider limits
      if (embeddingText.length() > MAX_EMBEDDING_TEXT_LENGTH) {
        embeddingText = embeddingText.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
      }

      float[] vector = embeddingProvider.embed(embeddingText, null);
      SemanticContent semanticContent = buildSemanticContent(vector, embeddingText);
      persistSemanticContent(entityUrn, semanticContent);

      log.info(
          "Successfully generated and stored embedding for dataset urn={} ({}D vector)",
          entityUrn,
          vector.length);
    } catch (Exception e) {
      // Log and swallow so a single embedding failure does not block other hooks in the pipeline
      log.error(
          "Failed to generate embedding for dataset urn={}: {}", entityUrn, e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------------------
  // Eligibility check
  // ---------------------------------------------------------------------------

  private boolean isEligibleForProcessing(@Nonnull MetadataChangeLog event) {
    if (!DATASET_ENTITY_NAME.equals(event.getEntityType())) {
      return false;
    }
    if (ChangeType.DELETE.equals(event.getChangeType())) {
      return false;
    }
    // Only re-embed when the aspects that contribute to the embedding text change.
    // Ignore semanticContent itself to prevent an infinite feedback loop.
    String aspect = event.getAspectName();
    return DATASET_PROPERTIES_ASPECT_NAME.equals(aspect)
        || SCHEMA_METADATA_ASPECT_NAME.equals(aspect);
  }

  // ---------------------------------------------------------------------------
  // Embedding text construction
  // ---------------------------------------------------------------------------

  /**
   * Fetches both {@code datasetProperties} and {@code schemaMetadata} for the given dataset and
   * composes the embedding text:
   *
   * <pre>
   * {name}
   * {description}
   * {col1} {col2} ...
   * </pre>
   *
   * @return the embedding text, or {@code null} if the dataset has no name information.
   */
  @Nullable
  @VisibleForTesting
  String buildEmbeddingText(@Nonnull Urn datasetUrn) {
    DatasetProperties props = fetchDatasetProperties(datasetUrn);
    SchemaMetadata schema = fetchSchemaMetadata(datasetUrn);

    String name = (props != null && props.hasName()) ? props.getName() : "";
    String description = (props != null && props.hasDescription()) ? props.getDescription() : "";
    String columns =
        (schema != null && schema.hasFields())
            ? schema.getFields().stream()
                .map(f -> f.getFieldPath())
                .collect(Collectors.joining(" "))
            : "";

    if (name.isBlank() && description.isBlank() && columns.isBlank()) {
      return null;
    }

    StringBuilder sb = new StringBuilder();
    if (!name.isBlank()) sb.append(name);
    if (!description.isBlank()) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(description);
    }
    if (!columns.isBlank()) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(columns);
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // SemanticContent aspect construction
  // ---------------------------------------------------------------------------

  @Nonnull
  private SemanticContent buildSemanticContent(@Nonnull float[] vector, @Nonnull String text) {
    com.linkedin.data.template.FloatArray floatArray = new com.linkedin.data.template.FloatArray();
    for (float v : vector) {
      floatArray.add(v);
    }

    EmbeddingChunk chunk = new EmbeddingChunk().setPosition(0).setVector(floatArray).setText(text);

    EmbeddingChunkArray chunks = new EmbeddingChunkArray();
    chunks.add(chunk);

    EmbeddingModelData modelData =
        new EmbeddingModelData()
            .setModelVersion(MODEL_VERSION)
            .setGeneratedAt(System.currentTimeMillis())
            .setChunkingStrategy(CHUNKING_STRATEGY)
            .setTotalChunks(1)
            .setChunks(chunks);

    EmbeddingModelDataMap embeddingsMap = new EmbeddingModelDataMap();
    embeddingsMap.put(MODEL_KEY, modelData);

    return new SemanticContent().setEmbeddings(embeddingsMap);
  }

  // ---------------------------------------------------------------------------
  // Proposal ingestion
  // ---------------------------------------------------------------------------

  private void persistSemanticContent(@Nonnull Urn entityUrn, @Nonnull SemanticContent content) {
    GenericAspect serialized = GenericRecordUtils.serializeAspect(content);

    MetadataChangeProposal proposal = new MetadataChangeProposal();
    proposal.setEntityUrn(entityUrn);
    proposal.setEntityType(DATASET_ENTITY_NAME);
    proposal.setAspectName("semanticContent");
    proposal.setAspect(serialized);
    proposal.setChangeType(ChangeType.UPSERT);

    try {
      systemEntityClient.ingestProposal(systemOperationContext, proposal, false);
    } catch (RemoteInvocationException e) {
      throw new RuntimeException(
          "Failed to ingest semanticContent aspect for dataset " + entityUrn, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Entity fetching helpers
  // ---------------------------------------------------------------------------

  @Nullable
  private DatasetProperties fetchDatasetProperties(@Nonnull Urn urn) {
    try {
      EntityResponse response =
          systemEntityClient.getV2(
              systemOperationContext, urn, ImmutableSet.of(DATASET_PROPERTIES_ASPECT_NAME));
      if (response != null
          && response.hasAspects()
          && response.getAspects().containsKey(DATASET_PROPERTIES_ASPECT_NAME)) {
        return new DatasetProperties(
            response.getAspects().get(DATASET_PROPERTIES_ASPECT_NAME).getValue().data());
      }
    } catch (RemoteInvocationException | URISyntaxException e) {
      log.warn("Failed to fetch datasetProperties for {}: {}", urn, e.getMessage());
    }
    return null;
  }

  @Nullable
  private SchemaMetadata fetchSchemaMetadata(@Nonnull Urn urn) {
    try {
      EntityResponse response =
          systemEntityClient.getV2(
              systemOperationContext, urn, ImmutableSet.of(SCHEMA_METADATA_ASPECT_NAME));
      if (response != null
          && response.hasAspects()
          && response.getAspects().containsKey(SCHEMA_METADATA_ASPECT_NAME)) {
        return new SchemaMetadata(
            response.getAspects().get(SCHEMA_METADATA_ASPECT_NAME).getValue().data());
      }
    } catch (RemoteInvocationException | URISyntaxException e) {
      log.warn("Failed to fetch schemaMetadata for {}: {}", urn, e.getMessage());
    }
    return null;
  }

  @Nonnull
  private Urn getUrn(@Nonnull MetadataChangeLog event) {
    try {
      return Urn.createFromString(event.getEntityUrn().toString());
    } catch (URISyntaxException e) {
      throw new RuntimeException("Invalid URN in MCL event: " + event.getEntityUrn(), e);
    }
  }
}
