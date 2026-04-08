#!/usr/bin/env bash
# Tutorial 3: Search Architecture - 학습 검증 테스트
#
# 사용법: ./docs/dev-guides/onboarding/tests/03-search-architecture-test.sh

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
section "1. @Searchable 어노테이션"
# ---------------------------------------------------------------------------

check "SearchableAnnotation.java 존재" \
  test -f "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/models/annotation/SearchableAnnotation.java"

check "SearchableAnnotation에 FieldType enum 정의" \
  grep -q "enum FieldType" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/models/annotation/SearchableAnnotation.java"

check "FieldType에 TEXT 정의" \
  grep -q "TEXT" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/models/annotation/SearchableAnnotation.java"

check "FieldType에 KEYWORD 정의" \
  grep -q "KEYWORD" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/models/annotation/SearchableAnnotation.java"

check "FieldType에 WORD_GRAM 정의" \
  grep -q "WORD_GRAM" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/models/annotation/SearchableAnnotation.java"

# ---------------------------------------------------------------------------
section "2. 매핑 빌더"
# ---------------------------------------------------------------------------

check "V2MappingsBuilder.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/index/entity/v2/V2MappingsBuilder.java"

check "V2MappingsBuilder에 getIndexMappings 관련 메서드" \
  grep -q "getIndexMappings\|getMappingsForField" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/index/entity/v2/V2MappingsBuilder.java"

check "DelegatingMappingsBuilder.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/index/DelegatingMappingsBuilder.java"

# ---------------------------------------------------------------------------
section "3. 검색 문서 변환"
# ---------------------------------------------------------------------------

check "SearchDocumentTransformer.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/transformer/SearchDocumentTransformer.java"

# ---------------------------------------------------------------------------
section "4. 검색 서비스 계층"
# ---------------------------------------------------------------------------

check "SearchService.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/SearchService.java"

check "SearchService에 searchAcrossEntities 메서드" \
  grep -q "searchAcrossEntities" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/SearchService.java"

check "CachingEntitySearchService.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/client/CachingEntitySearchService.java"

check "ElasticSearchService.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/ElasticSearchService.java"

check "ESSearchDAO.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/query/ESSearchDAO.java"

# ---------------------------------------------------------------------------
section "5. 검색 쿼리 빌드"
# ---------------------------------------------------------------------------

check "SearchRequestHandler.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/query/request/SearchRequestHandler.java"

# ---------------------------------------------------------------------------
section "6. 인덱스 설정"
# ---------------------------------------------------------------------------

check "V2LegacySettingsBuilder.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/search/elasticsearch/index/entity/v2/V2LegacySettingsBuilder.java"

# ---------------------------------------------------------------------------
section "결과"
# ---------------------------------------------------------------------------
echo ""
bold "총 ${TOTAL}개 테스트: ${PASS}개 통과, ${FAIL}개 실패"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi