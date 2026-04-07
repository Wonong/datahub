#!/usr/bin/env bash
# Tutorial 1: Entity-Aspect Model - 학습 검증 테스트
#
# 사용법: ./docs/dev-guides/onboarding/tests/01-entity-aspect-model-test.sh
#
# 이 스크립트는 코드베이스를 직접 탐색하여 튜토리얼의 핵심 개념을 검증합니다.
# 모든 테스트가 통과하면 Entity-Aspect 모델을 충분히 이해한 것입니다.

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
section "1. entity-registry.yml 구조 이해"
# ---------------------------------------------------------------------------

REGISTRY="$REPO_ROOT/metadata-models/src/main/resources/entity-registry.yml"

check "entity-registry.yml 파일 존재" test -f "$REGISTRY"

check "dataset 엔티티가 정의되어 있음" \
  grep -q "name: dataset" "$REGISTRY"

check "dataset의 keyAspect가 datasetKey임" \
  sed -n '/^  - name: dataset$/,/^  - name: /p' "$REGISTRY" | grep -q "keyAspect: datasetKey"

check "dataPlatform 엔티티가 정의되어 있음 (단순 엔티티)" \
  grep -q "name: dataPlatform" "$REGISTRY"

check "corpuser 엔티티가 정의되어 있음" \
  grep -q "name: corpuser" "$REGISTRY"

# dataset의 aspect 수 확인 (10개 이상이면 복잡한 엔티티)
DATASET_ASPECTS=$(sed -n '/^  - name: dataset$/,/^  - name: /p' "$REGISTRY" | grep -c "      - " || true)
check "dataset 엔티티는 10개 이상의 aspect를 가짐 (실제: $DATASET_ASPECTS)" \
  [ "$DATASET_ASPECTS" -ge 10 ]

# ---------------------------------------------------------------------------
section "2. PDL 스키마 이해"
# ---------------------------------------------------------------------------

DATASET_PROPS="$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/dataset/DatasetProperties.pdl"
DATASET_KEY="$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/metadata/key/DatasetKey.pdl"

check "DatasetProperties.pdl 파일 존재" test -f "$DATASET_PROPS"
check "DatasetKey.pdl 파일 존재" test -f "$DATASET_KEY"

check "DatasetProperties에 @Aspect 어노테이션 존재" \
  grep -q '@Aspect' "$DATASET_PROPS"

check "DatasetProperties의 aspect 이름이 'datasetProperties'임" \
  grep -q '"name": "datasetProperties"' "$DATASET_PROPS"

check "DatasetProperties에 @Searchable 어노테이션이 있는 필드 존재" \
  grep -q '@Searchable' "$DATASET_PROPS"

check "DatasetProperties의 name 필드가 WORD_GRAM 타입으로 검색 가능" \
  grep -A5 'name: optional string' "$DATASET_PROPS" | head -6 | grep -q 'WORD_GRAM' || \
  grep -B5 'name: optional string' "$DATASET_PROPS" | grep -q 'WORD_GRAM'

check "DatasetKey에 platform, name, origin 필드가 있음" \
  grep -q 'platform:' "$DATASET_KEY" && grep -q 'name:' "$DATASET_KEY" && grep -q 'origin:' "$DATASET_KEY"

# ---------------------------------------------------------------------------
section "3. @Relationship 어노테이션"
# ---------------------------------------------------------------------------

LINEAGE="$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/dataset/UpstreamLineage.pdl"

check "UpstreamLineage.pdl 파일 존재" test -f "$LINEAGE"

check "UpstreamLineage에 @Relationship 어노테이션 존재" \
  grep -q '@Relationship' "$LINEAGE"

# ---------------------------------------------------------------------------
section "4. URN 구조"
# ---------------------------------------------------------------------------

URN_UTILS="$REPO_ROOT/li-utils/src/main/javaPegasus/com/linkedin/common/urn/UrnUtils.java"

check "UrnUtils.java 파일 존재" test -f "$URN_UTILS"

check "UrnUtils에 getUrn 메서드 존재" \
  grep -q 'getUrn' "$URN_UTILS"

# URN 관련 클래스 확인
check "DatasetUrn 클래스 존재" \
  find "$REPO_ROOT/metadata-models/src/main/java" -name "DatasetUrn.java" | grep -q .

# ---------------------------------------------------------------------------
section "5. 런타임 메타데이터 클래스"
# ---------------------------------------------------------------------------

check "EntityRegistry 인터페이스 존재" \
  find "$REPO_ROOT/entity-registry/src/main/java" -name "EntityRegistry.java" | grep -q .

check "EntitySpec 클래스 존재" \
  find "$REPO_ROOT/entity-registry/src/main/java" -name "EntitySpec.java" | grep -q .

check "AspectSpec 클래스 존재" \
  find "$REPO_ROOT/entity-registry/src/main/java" -name "AspectSpec.java" | grep -q .

check "SearchableAnnotation 클래스 존재" \
  find "$REPO_ROOT/entity-registry/src/main/java" -name "SearchableAnnotation.java" | grep -q .

check "EntityRegistryFactory 존재" \
  find "$REPO_ROOT/metadata-service/factories" -name "EntityRegistryFactory.java" | grep -q .

# AspectSpec에 searchableFieldSpecs 관련 메서드가 있는지
ASPECT_SPEC=$(find "$REPO_ROOT/entity-registry/src/main/java" -name "AspectSpec.java" -print -quit)
check "AspectSpec에 searchableFieldSpecs 존재" \
  grep -q 'searchableFieldSpec' "$ASPECT_SPEC"

# ---------------------------------------------------------------------------
section "6. 심화: 엔티티 수 확인"
# ---------------------------------------------------------------------------

ENTITY_COUNT=$(grep -c "^  - name:" "$REGISTRY" || true)
check "entity-registry에 40개 이상의 엔티티 정의 (실제: $ENTITY_COUNT)" \
  [ "$ENTITY_COUNT" -ge 40 ]

# category 확인
CORE_COUNT=$(grep -B0 -A1 "^  - name:" "$REGISTRY" | grep -c "category: core" || true)
INTERNAL_COUNT=$(grep -B0 -A1 "^  - name:" "$REGISTRY" | grep -c "category: internal" || true)
check "core 카테고리 엔티티가 존재 (실제: $CORE_COUNT)" \
  [ "$CORE_COUNT" -gt 0 ]
check "internal 카테고리 엔티티가 존재 (실제: $INTERNAL_COUNT)" \
  [ "$INTERNAL_COUNT" -gt 0 ]

# ---------------------------------------------------------------------------
# 결과 요약
# ---------------------------------------------------------------------------
echo ""
bold "==========================================="
if [ "$FAIL" -eq 0 ]; then
  green "  ALL PASSED: $PASS/$TOTAL tests passed"
  bold "==========================================="
  echo ""
  green "Tutorial 1 완료! Entity-Aspect 모델을 이해했습니다."
  echo "다음 단계: Tutorial 2 - 쓰기 경로 (Ingestion)"
  exit 0
else
  red "  FAILED: $FAIL/$TOTAL tests failed"
  bold "==========================================="
  echo ""
  red "실패한 항목을 튜토리얼을 참고하여 다시 확인해보세요."
  exit 1
fi
