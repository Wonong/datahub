#!/usr/bin/env bash
# Tutorial 4: GraphQL API Layer - 학습 검증 테스트
#
# 사용법: ./docs/dev-guides/onboarding/tests/04-graphql-api-test.sh

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
PASS=0
FAIL=0
TOTAL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
bold()  { printf "\033[1m%s\033[0m\n" "$1"; }

check() {
  TOTAL=$((TOTAL + 1))
  local desc="$1"
  shift
  if "$@" > /dev/null 2>&1; then
    green "  PASS: $desc"
    PASS=$((PASS + 1))
  else
    red "  FAIL: $desc"
    FAIL=$((FAIL + 1))
  fi
}

section() {
  echo ""
  bold "=== $1 ==="
}

# ---------------------------------------------------------------------------
section "1. GraphQL 스키마 파일"
# ---------------------------------------------------------------------------

check "search.graphql 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/resources/search.graphql"

check "entity.graphql 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/resources/entity.graphql"

GRAPHQL_COUNT=$(find "$REPO_ROOT/datahub-graphql-core/src/main/resources/" -name "*.graphql" | wc -l | tr -d ' ')
check ".graphql 파일 10개 이상 (현재: ${GRAPHQL_COUNT}개)" \
  test "$GRAPHQL_COUNT" -ge 10

# ---------------------------------------------------------------------------
section "2. GmsGraphQLEngine"
# ---------------------------------------------------------------------------

check "GmsGraphQLEngine.java 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngine.java"

check "GmsGraphQLEngine에 configureQueryResolvers 메서드" \
  grep -q "configureQueryResolvers\|configureDatasetResolvers\|configureSearchResolvers" "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngine.java"

check "GmsGraphQLEngineArgs.java 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/GmsGraphQLEngineArgs.java"

# ---------------------------------------------------------------------------
section "3. 리졸버 패턴"
# ---------------------------------------------------------------------------

check "SearchAcrossEntitiesResolver.java 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/search/SearchAcrossEntitiesResolver.java"

check "SearchAcrossEntitiesResolver가 DataFetcher 구현" \
  grep -q "DataFetcher" "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/search/SearchAcrossEntitiesResolver.java"

check "ResolverUtils.java 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/resolvers/ResolverUtils.java"

# ---------------------------------------------------------------------------
section "4. 타입 매퍼"
# ---------------------------------------------------------------------------

check "UrnSearchResultsMapper.java 존재" \
  test -f "$REPO_ROOT/datahub-graphql-core/src/main/java/com/linkedin/datahub/graphql/types/mappers/UrnSearchResultsMapper.java"

# ---------------------------------------------------------------------------
section "5. EntityClient"
# ---------------------------------------------------------------------------

check "EntityClient.java 존재" \
  test -f "$REPO_ROOT/metadata-service/restli-client-api/src/main/java/com/linkedin/entity/client/EntityClient.java"

check "EntityClient에 searchAcrossEntities 메서드" \
  grep -q "searchAcrossEntities" "$REPO_ROOT/metadata-service/restli-client-api/src/main/java/com/linkedin/entity/client/EntityClient.java"

# ---------------------------------------------------------------------------
section "6. GraphQLEngineFactory"
# ---------------------------------------------------------------------------

check "GraphQLEngineFactory.java 존재" \
  test -f "$REPO_ROOT/metadata-service/factories/src/main/java/com/linkedin/gms/factory/graphql/GraphQLEngineFactory.java"

# ---------------------------------------------------------------------------
section "결과"
# ---------------------------------------------------------------------------
echo ""
bold "총 ${TOTAL}개 테스트: ${PASS}개 통과, ${FAIL}개 실패"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi