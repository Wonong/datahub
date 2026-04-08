#!/usr/bin/env bash
set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

PASS=0; FAIL=0; TOTAL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
bold()  { printf "\033[1m%s\033[0m\n" "$1"; }

check() {
  TOTAL=$((TOTAL + 1))
  local desc="$1"; shift
  if "$@" > /dev/null 2>&1; then
    green "  PASS: $desc"; PASS=$((PASS + 1))
  else
    red "  FAIL: $desc"; FAIL=$((FAIL + 1))
  fi
}

bold "=== Tutorial 02: Write Path (Ingestion) ==="
echo ""

# --- MCP Schema ---
bold ">> MetadataChangeProposal PDL"
check "MetadataChangeProposal.pdl exists" \
  test -f "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeProposal.pdl"

check "MCP has entityType field" \
  grep -q "entityType" "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeProposal.pdl"

check "MCP has changeType field" \
  grep -q "changeType" "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeProposal.pdl"

check "MCP has aspectName field" \
  grep -q "aspectName" "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeProposal.pdl"

# --- EntityService ---
bold ">> EntityService"
check "EntityService.java exists" \
  test -f "$REPO_ROOT/metadata-service/services/src/main/java/com/linkedin/metadata/entity/EntityService.java"

check "EntityService has ingestProposal method" \
  grep -q "ingestProposal" "$REPO_ROOT/metadata-service/services/src/main/java/com/linkedin/metadata/entity/EntityService.java"

check "EntityServiceImpl.java exists" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java"

check "EntityServiceImpl has ingestProposalSync method" \
  grep -q "ingestProposalSync" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java"

check "EntityServiceImpl has ingestAspectsToLocalDB method" \
  grep -q "ingestAspectsToLocalDB" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java"

check "EntityServiceImpl has produceMCLAsync method" \
  grep -q "produceMCLAsync" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java"

# --- AspectsBatch ---
bold ">> AspectsBatch"
check "AspectsBatchImpl.java exists" \
  test -f "$REPO_ROOT/metadata-io/metadata-io-api/src/main/java/com/linkedin/metadata/entity/ebean/batch/AspectsBatchImpl.java"

check "AspectsBatchImpl has toUpsertBatchItems method" \
  grep -q "toUpsertBatchItems" "$REPO_ROOT/metadata-io/metadata-io-api/src/main/java/com/linkedin/metadata/entity/ebean/batch/AspectsBatchImpl.java"

check "AspectsBatchImpl references validateProposed" \
  grep -q "validateProposed" "$REPO_ROOT/metadata-io/metadata-io-api/src/main/java/com/linkedin/metadata/entity/ebean/batch/AspectsBatchImpl.java"

# --- Validation ---
bold ">> AspectPayloadValidator"
check "AspectPayloadValidator.java exists" \
  test -f "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/aspect/plugins/validation/AspectPayloadValidator.java"

check "AspectPayloadValidator has validateProposed method" \
  grep -q "validateProposed" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/aspect/plugins/validation/AspectPayloadValidator.java"

check "AspectPayloadValidator has validatePreCommit method" \
  grep -q "validatePreCommit" "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/aspect/plugins/validation/AspectPayloadValidator.java"

check "FieldPathValidator.java exists" \
  test -f "$REPO_ROOT/entity-registry/src/main/java/com/linkedin/metadata/aspect/validation/FieldPathValidator.java"

# --- MCL ---
bold ">> MetadataChangeLog"
check "MetadataChangeLog.pdl exists" \
  test -f "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeLog.pdl"

check "MCL includes MetadataChangeProposal" \
  grep -q "includes MetadataChangeProposal" "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeLog.pdl"

check "MCL has previousAspectValue field" \
  grep -q "previousAspectValue" "$REPO_ROOT/metadata-models/src/main/pegasus/com/linkedin/mxe/MetadataChangeLog.pdl"

# --- UpdateIndicesService ---
bold ">> UpdateIndicesService"
check "UpdateIndicesService.java exists" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/service/UpdateIndicesService.java"

# --- DB Storage ---
bold ">> DB Storage (EbeanAspectV2)"
check "EbeanAspectV2.java exists" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/ebean/EbeanAspectV2.java"

check "EbeanAspectV2 maps to metadata_aspect_v2 table" \
  grep -q "metadata_aspect_v2" "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/ebean/EbeanAspectV2.java"

check "EbeanAspectDao.java exists" \
  test -f "$REPO_ROOT/metadata-io/src/main/java/com/linkedin/metadata/entity/ebean/EbeanAspectDao.java"

# --- Summary ---
echo ""
bold "=== Results: $PASS passed, $FAIL failed, $TOTAL total ==="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
