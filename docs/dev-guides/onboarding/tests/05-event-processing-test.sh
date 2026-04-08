#!/usr/bin/env bash
# Tutorial 5: Event Processing & Hooks - 학습 검증 테스트
#
# 사용법: ./docs/dev-guides/onboarding/tests/05-event-processing-test.sh

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

MCL_PDL="$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeLog.pdl"
HOOK_DIR="$REPO_ROOT/metadata-jobs/mae-consumer/src/main/java/com/linkedin/metadata/kafka/hook"
LISTENER_DIR="$REPO_ROOT/metadata-jobs/mae-consumer/src/main/java/com/linkedin/metadata/kafka/listener/mcl"

# ---------------------------------------------------------------------------
section "1. 이벤트 모델"
# ---------------------------------------------------------------------------

check "MetadataChangeLog.pdl 존재" \
  test -f "$MCL_PDL"

# ---------------------------------------------------------------------------
section "2. MCL Consumer"
# ---------------------------------------------------------------------------

check "MCLKafkaListener.java 존재" \
  test -f "$LISTENER_DIR/MCLKafkaListener.java"

# ---------------------------------------------------------------------------
section "3. MetadataChangeLogHook 인터페이스"
# ---------------------------------------------------------------------------

check "MetadataChangeLogHook.java 존재" \
  test -f "$HOOK_DIR/MetadataChangeLogHook.java"

check "MetadataChangeLogHook에 invoke 메서드" \
  grep -q "invoke" "$HOOK_DIR/MetadataChangeLogHook.java"

# ---------------------------------------------------------------------------
section "4. Hook 구현체"
# ---------------------------------------------------------------------------

check "UpdateIndicesHook.java 존재" \
  test -f "$HOOK_DIR/UpdateIndicesHook.java"

check "UpdateIndicesHook이 MetadataChangeLogHook 구현" \
  grep -q "MetadataChangeLogHook" "$HOOK_DIR/UpdateIndicesHook.java"

check "SiblingAssociationHook.java 존재" \
  test -f "$HOOK_DIR/siblings/SiblingAssociationHook.java"

check "FormAssignmentHook.java 존재" \
  bash -c "find '$REPO_ROOT/metadata-jobs' -name 'FormAssignmentHook.java' | grep -q ."

check "IncidentsSummaryHook.java 존재" \
  bash -c "find '$REPO_ROOT/metadata-jobs' -name 'IncidentsSummaryHook.java' | grep -q ."

HOOK_COUNT=$(find "$REPO_ROOT/metadata-jobs" -name "*Hook.java" -path "*/hook/*" ! -path "*/test/*" | wc -l | tr -d ' ')
check "Hook 구현체 4개 이상 (현재: ${HOOK_COUNT}개)" \
  test "$HOOK_COUNT" -ge 4

# ---------------------------------------------------------------------------
section "5. UpdateIndicesService"
# ---------------------------------------------------------------------------

check "UpdateIndicesService.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/service/UpdateIndicesService.java"

check "UpdateIndicesV2Strategy.java 존재" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/service/UpdateIndicesV2Strategy.java"

check "UpdateIndicesHook에 updateIndicesService 필드" \
  grep -q "updateIndicesService\|UpdateIndicesService" "$HOOK_DIR/UpdateIndicesHook.java"

# ---------------------------------------------------------------------------
section "결과"
# ---------------------------------------------------------------------------
echo ""
bold "총 ${TOTAL}개 테스트: ${PASS}개 통과, ${FAIL}개 실패"

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi