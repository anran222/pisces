#!/usr/bin/env bash

set -euo pipefail

CHECKS_PASSED=0
WARNINGS=0

usage() {
  cat <<'USAGE'
Usage:
  scripts/runtime-plane-release-package-check.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_RELEASE_PACKAGE_RUN_TESTS         Run Maven and SDK focused tests. Default: false.
  PISCES_RELEASE_PACKAGE_REPORT_FILE       JSON report file. Default: target/pisces-runtime-release-package-check/report.json.
  PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL  Fail when promtool is missing. Default: false.
  PISCES_RELEASE_PACKAGE_REQUIRE_RUBY      Fail when ruby YAML parser is missing. Default: false.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

warn() {
  WARNINGS=$((WARNINGS + 1))
  log "WARN: $*"
}

pass() {
  CHECKS_PASSED=$((CHECKS_PASSED + 1))
  log "OK: $*"
}

is_true() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|Yes|y|Y)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

command_available() {
  if command -v "$1" >/dev/null 2>&1; then
    printf 'true'
    return
  fi
  printf 'false'
}

resolve_repo_root() {
  if [[ -n "${PISCES_REPO_ROOT:-}" ]]; then
    (cd "$PISCES_REPO_ROOT" && pwd)
    return
  fi

  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if command -v git >/dev/null 2>&1 && git -C "$script_dir/.." rev-parse --show-toplevel >/dev/null 2>&1; then
    git -C "$script_dir/.." rev-parse --show-toplevel
    return
  fi
  (cd "$script_dir/.." && pwd)
}

resolve_git_sha() {
  if [[ -n "${PISCES_GIT_SHA:-}" ]]; then
    printf '%s' "$PISCES_GIT_SHA"
    return
  fi
  if command -v git >/dev/null 2>&1 && git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    git -C "$PISCES_REPO_ROOT" rev-parse HEAD
    return
  fi
  printf 'unknown'
}

resolve_git_dirty() {
  if command -v git >/dev/null 2>&1 && git -C "$PISCES_REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    if [[ -n "$(git -C "$PISCES_REPO_ROOT" status --porcelain)" ]]; then
      printf 'true'
      return
    fi
    printf 'false'
    return
  fi
  printf 'unknown'
}

resolve_report_file() {
  case "$1" in
    /*)
      printf '%s' "$1"
      ;;
    *)
      printf '%s/%s' "$PISCES_REPO_ROOT" "$1"
      ;;
  esac
}

check_file() {
  local relative_path="$1"
  [[ -f "$PISCES_REPO_ROOT/$relative_path" ]] || die "Missing release package file: $relative_path"
  pass "file exists: $relative_path"
}

check_pattern() {
  local relative_path="$1"
  local pattern="$2"
  local description="$3"
  grep -Eq -- "$pattern" "$PISCES_REPO_ROOT/$relative_path" || die "Missing contract marker: $description in $relative_path"
  pass "contract marker: $description"
}

check_bash_syntax() {
  local relative_path="$1"
  bash -n "$PISCES_REPO_ROOT/$relative_path"
  pass "bash syntax: $relative_path"
}

check_json() {
  local relative_path="$1"
  python3 -m json.tool "$PISCES_REPO_ROOT/$relative_path" >/dev/null
  pass "json parse: $relative_path"
}

check_yaml() {
  local relative_path="$1"
  if command -v ruby >/dev/null 2>&1; then
    ruby -e 'require "yaml"; YAML.load_file(ARGV[0])' "$PISCES_REPO_ROOT/$relative_path"
    pass "yaml parse: $relative_path"
    return
  fi

  if is_true "${PISCES_RELEASE_PACKAGE_REQUIRE_RUBY:-false}"; then
    die "Missing command: ruby"
  fi
  warn "ruby is not installed; skipped YAML parse for $relative_path"
}

check_promtool_rules() {
  local relative_path="$1"
  if command -v promtool >/dev/null 2>&1; then
    promtool check rules "$PISCES_REPO_ROOT/$relative_path"
    pass "promtool rules: $relative_path"
    return
  fi

  if is_true "${PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL:-false}"; then
    die "Missing command: promtool"
  fi
  warn "promtool is not installed; skipped Prometheus rule semantic check for $relative_path"
}

check_required_files() {
  local -a required_files=(
    "pom.xml"
    "config/pisces-local.env.example"
    "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java"
    "pisces-api/src/test/java/com/pisces/api/runtime/RuntimeConfigControllerContractTest.java"
    "pisces-common/src/main/java/com/pisces/common/request/ExperimentConclusionStatusUpdateRequest.java"
    "pisces-common/src/main/java/com/pisces/common/model/ExperimentMetadata.java"
    "pisces-common/src/main/java/com/pisces/common/response/ExperimentResponse.java"
    "pisces-common/src/main/java/com/pisces/common/response/RuntimeExperimentConfigResponse.java"
    "pisces-common/src/main/java/com/pisces/common/response/RuntimeExperimentConfigVersionResponse.java"
    "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java"
    "pisces-service/src/main/java/com/pisces/service/service/impl/RuntimeConfigServiceImpl.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/RuntimeConfigServiceImplTest.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/ExperimentServiceImplTest.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/ProductionExperimentFlowSmokeTest.java"
    "pisces-service/src/main/java/com/pisces/service/event/EventReplayProgressReporter.java"
    "pisces-service/src/test/java/com/pisces/service/metrics/EventReplayMetricsTest.java"
    "pisces-service/src/test/java/com/pisces/service/config/EventReplayExecutorConfigTest.java"
    "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job_scope_migration.sql"
    "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job_progress_migration.sql"
    "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_scope_index_migration.sql"
    "pisces-service/src/test/java/com/pisces/service/service/impl/AIDecisionServiceImplTest.java"
    "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplAIBridgeTest.java"
    "pisces-service/src/test/java/com/pisces/service/ai/ExperimentDecisionContextBuilderTest.java"
    "pisces-service/src/test/java/com/pisces/service/ai/PromptTemplateBuilderTest.java"
    "pisces-common/src/test/java/com/pisces/common/response/AIDecisionResponseShapeTest.java"
    "pisces-api/src/test/java/com/pisces/api/analysis/AnalysisControllerAIDiagnosisTest.java"
    "pisces-api/src/test/java/com/pisces/api/analysis/AnalysisControllerAIGraduationDecisionTest.java"
    "pisces-sdk-java/README.md"
    "pisces-sdk-java/pom.xml"
    "pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java"
    "pisces-sdk-java/src/test/java/com/pisces/sdk/PiscesClientTest.java"
    "pisces-sdk-js/README.md"
    "pisces-sdk-js/package.json"
    "pisces-sdk-js/pisces-sdk.js"
    "pisces-sdk-js/test/pisces-sdk.test.js"
    "compose.local.yml"
    "scripts/production-infrastructure-completion-audit.sh"
    "scripts/production-infrastructure-completion-audit-smoke-test.sh"
    "scripts/production-infrastructure-closeout.sh"
    "scripts/production-infrastructure-local-bootstrap.sh"
    "scripts/production-infrastructure-local-bootstrap-smoke-test.sh"
    "scripts/production-infrastructure-local-dependency-stack.sh"
    "scripts/production-infrastructure-local-dependency-stack-smoke-test.sh"
    "scripts/production-infrastructure-local-dependency-check.sh"
    "scripts/production-infrastructure-local-dependency-check-smoke-test.sh"
    "scripts/production-infrastructure-local-mysql-schema-apply.sh"
    "scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh"
    "scripts/production-infrastructure-local-service.sh"
    "scripts/production-infrastructure-local-service-smoke-test.sh"
    "scripts/production-infrastructure-local-ai-smoke.sh"
    "scripts/production-infrastructure-local-ai-smoke-smoke-test.sh"
    "scripts/production-infrastructure-local-frontend-evidence.sh"
    "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-collect.sh"
    "scripts/production-infrastructure-local-evidence-collect-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-workspace.sh"
    "scripts/production-infrastructure-local-evidence-workspace-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-validate.sh"
    "scripts/production-infrastructure-local-evidence-validate-smoke-test.sh"
    "scripts/production-infrastructure-local-prekey-check.sh"
    "scripts/production-infrastructure-local-prekey-check-smoke-test.sh"
    "scripts/production-infrastructure-local-completion-verify.sh"
    "scripts/production-infrastructure-local-completion-verify-smoke-test.sh"
    "scripts/production-infrastructure-local-finalize.sh"
    "scripts/production-infrastructure-local-finalize-smoke-test.sh"
    "scripts/production-infrastructure-local-closeout.sh"
    "scripts/production-infrastructure-local-readiness.sh"
    "scripts/production-infrastructure-secret-scan.sh"
    "scripts/runtime-plane-release-drill.sh"
    "scripts/runtime-plane-capacity-baseline.sh"
    "scripts/runtime-plane-archive-baseline.sh"
    "scripts/runtime-plane-redis-fault-injection.sh"
    "scripts/runtime-plane-release-package-check.sh"
    "scripts/runtime-plane-release-evidence-archive.sh"
    "scripts/runtime-plane-release-evidence-archive-smoke-test.sh"
    "scripts/runtime-plane-release-evidence-strict-smoke-test.sh"
    "scripts/runtime-plane-preprod-drill-record-check.sh"
    "scripts/runtime-plane-preprod-drill-record-smoke-test.sh"
    "scripts/runtime-plane-production-acceptance-check.sh"
    "scripts/runtime-plane-production-acceptance-smoke-test.sh"
    "scripts/runtime-plane-post-release-slo-review.sh"
    "scripts/runtime-plane-experiment-impact-sampling.sh"
    "scripts/runtime-plane-staged-rollout-decision.sh"
    "scripts/event-pipeline-replay-audit.sh"
    "scripts/event-pipeline-replay-audit-scope-smoke-test.sh"
    "scripts/event-pipeline-replay-segment-repair-smoke-test.sh"
    "../pisces-web/package.json"
    "../pisces-web/scripts/capture-core-functions.cjs"
    ".github/workflows/runtime-plane-release-package.yml"
    "docs/operations/runtime-config-contract-matrix.md"
    "docs/operations/production-infrastructure-completion-audit.md"
    "docs/operations/runtime-plane-preprod-drill-record-template.md"
    "docs/operations/runtime-plane-preprod-drill-record-sample.md"
    "docs/operations/runtime-plane-release-evidence-archive.md"
    "docs/operations/runtime-plane-post-release-slo-review.md"
    "docs/operations/runtime-plane-post-release-slo-sample.json"
    "docs/operations/runtime-plane-experiment-impact-sampling.md"
    "docs/operations/runtime-plane-staged-rollout-decision.md"
    "docs/operations/runtime-plane-staged-rollout-acceptance-sample.json"
    "docs/operations/runtime-plane-production-acceptance.md"
    "docs/operations/runtime-plane-production-acceptance-sample.json"
    "docs/operations/runtime-plane-rollback-decision-drill-template.md"
    "docs/operations/runtime-plane-post-release-incident-review-template.md"
    "docs/operations/event-pipeline-replay-audit.md"
    "docs/operations/event-pipeline-replay-audit-sample.json"
    "docs/operations/runtime-plane-release-drill.md"
    "docs/operations/runtime-plane-release-checklist.md"
    "docs/operations/runtime-plane-release-package-check.md"
    "docs/operations/runtime-plane-capacity-baseline.md"
    "docs/operations/runtime-plane-baseline-archive.md"
    "docs/operations/runtime-plane-redis-fault-injection.md"
    "docs/observability/runtime-plane-runbook.md"
    "docs/observability/sdk-metrics-integration.md"
    "docs/observability/prometheus/pisces-runtime-plane-alerts.yml"
    "docs/observability/grafana/pisces-runtime-plane-dashboard.json"
  )

  local required_file
  for required_file in "${required_files[@]}"; do
    check_file "$required_file"
  done
}

check_runtime_contract_markers() {
  check_pattern "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java" \
    '@RequestMapping\("/runtime/experiments"\)' "runtime config controller route"
  check_pattern "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java" \
    '@ApiKeyScopeRequired\(ApiKeyScope.RUNTIME\)' "runtime scope enforcement"
  check_pattern "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java" \
    '@NoTokenRequired' "runtime API key entrypoint without token"
  check_pattern "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java" \
    '@RequestParam\(value = "knownVersion", required = false\)' "knownVersion optional query param"
  check_pattern "pisces-api/src/main/java/com/pisces/api/runtime/RuntimeConfigController.java" \
    '@RequestParam\(value = "waitMillis", required = false\)' "waitMillis optional query param"
  check_pattern "pisces-api/src/test/java/com/pisces/api/runtime/RuntimeConfigControllerContractTest.java" \
    'getExperimentConfigShouldReturnRuntimeContractShape' "full runtime config HTTP contract test"
  check_pattern "pisces-api/src/test/java/com/pisces/api/runtime/RuntimeConfigControllerContractTest.java" \
    'getExperimentConfigShouldPreserveEmptyCollectionsInHttpResponse' "empty collection HTTP contract test"
  check_pattern "pisces-api/src/test/java/com/pisces/api/runtime/RuntimeConfigControllerContractTest.java" \
    'getExperimentConfigVersionShouldBindKnownVersionAndWaitMillis' "version query binding contract test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/RuntimeConfigServiceImplTest.java" \
    'getExperimentConfigShouldReturnEmptyCollectionsForOptionalRuntimeAssets' "runtime service empty collection contract test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/RuntimeConfigServiceImplTest.java" \
    'getExperimentConfigShouldReturnEmptyGroupConfigWhenGroupConfigIsMissing' "runtime service empty group config contract test"
}

check_ai_decision_evidence_markers() {
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java" \
    'private Boolean analysisReady' "AI decision evidence analysis readiness"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java" \
    'private List<String> blockingIssues' "AI decision evidence blocking issues"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/AIDecisionEvidenceResponse.java" \
    'private Integer latestReportSnapshotVersion' "AI decision evidence latest report snapshot version"
  check_pattern "pisces-common/src/main/java/com/pisces/common/model/ExperimentDecisionContext.java" \
    'private List<String> reportSnapshotFacts' "AI decision context report snapshot facts"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/AIDiagnosisResponse.java" \
    'private AIDecisionEvidenceResponse evidence' "AI diagnosis evidence field"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/AIGraduationDecisionResponse.java" \
    'private AIDecisionEvidenceResponse evidence' "AI graduation evidence field"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AIDecisionServiceImpl.java" \
    'buildDecisionEvidence' "AI decision service binds platform evidence"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AIDecisionServiceImpl.java" \
    'response\.setEvidence\(buildDecisionEvidence\(context\)\)' "AI decision responses attach evidence"
  check_pattern "pisces-service/src/main/java/com/pisces/service/ai/ExperimentDecisionContextBuilder.java" \
    'listReportSnapshots' "AI decision context binds latest report snapshot"
  check_pattern "pisces-service/src/main/java/com/pisces/service/ai/PromptTemplateBuilder.java" \
    'reportSnapshotFacts' "AI prompt includes report snapshot facts"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AIDecisionServiceImplTest.java" \
    'decisionContextWithStatistics' "AI decision service evidence test fixture"
  check_pattern "pisces-service/src/test/java/com/pisces/service/ai/ExperimentDecisionContextBuilderTest.java" \
    'buildForExperimentShouldBindLatestReportSnapshotFacts' "AI context latest report snapshot test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/ai/PromptTemplateBuilderTest.java" \
    'latestReportSnapshotVersion' "AI prompt report snapshot evidence test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplAIBridgeTest.java" \
    'LatestReportSnapshotVersion' "AI legacy bridge evidence test"
  check_pattern "pisces-common/src/test/java/com/pisces/common/response/AIDecisionResponseShapeTest.java" \
    'shouldExposeAIDecisionEvidenceResponseFields' "AI decision evidence response shape test"
  check_pattern "pisces-api/src/test/java/com/pisces/api/analysis/AnalysisControllerAIDiagnosisTest.java" \
    'data\.evidence\.analysisReady' "AI diagnosis HTTP evidence contract test"
  check_pattern "pisces-api/src/test/java/com/pisces/api/analysis/AnalysisControllerAIGraduationDecisionTest.java" \
    'data\.evidence\.analysisReady' "AI graduation HTTP evidence contract test"
}

check_manual_conclusion_markers() {
  check_pattern "pisces-common/src/main/java/com/pisces/common/request/ExperimentConclusionStatusUpdateRequest.java" \
    'private Long expectedConfigVersion' "manual conclusion expected config version request field"
  check_pattern "pisces-common/src/main/java/com/pisces/common/request/ExperimentConclusionStatusUpdateRequest.java" \
    'private Integer reportSnapshotVersion' "manual conclusion report snapshot request field"
  check_pattern "pisces-common/src/main/java/com/pisces/common/model/ExperimentMetadata.java" \
    'private Long conclusionConfigVersion' "manual conclusion config version binding"
  check_pattern "pisces-common/src/main/java/com/pisces/common/model/ExperimentMetadata.java" \
    'private Integer conclusionReportSnapshotVersion' "manual conclusion report snapshot binding"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/ExperimentResponse.java" \
    'private Long conclusionConfigVersion' "manual conclusion binding visible in experiment response"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/ExperimentServiceImpl.java" \
    'validateConclusionEvidence' "manual conclusion evidence validation"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/ExperimentServiceImpl.java" \
    'resetConclusionAfterConfigChange' "manual conclusion reset after config change"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/ExperimentServiceImpl.java" \
    'applyConclusionSnapshotAuditDetail' "manual conclusion audit includes report snapshot evidence"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/ExperimentServiceImplTest.java" \
    'updateConclusionStatusShouldBindConfigAndReportSnapshotEvidence' "manual conclusion evidence binding test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/ExperimentServiceImplTest.java" \
    'rollbackConfigVersionShouldCreateNewVersionFromPublishedSnapshot' "manual conclusion reset covered by rollback test"
}

check_sdk_contract_markers() {
  check_pattern "pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java" \
    'configVersionLongPollMillis' "Java SDK version long-poll config"
  check_pattern "pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java" \
    'getMetricsSnapshot' "Java SDK metrics snapshot"
  check_pattern "pisces-sdk-java/src/test/java/com/pisces/sdk/PiscesClientTest.java" \
    'stale|retry|MetricsSnapshot|configVersionLongPollMillis' "Java SDK runtime resilience tests"
  check_pattern "pisces-sdk-js/pisces-sdk.js" \
    'configVersionLongPollMillis' "JS SDK version long-poll config"
  check_pattern "pisces-sdk-js/pisces-sdk.js" \
    'getMetricsSnapshot' "JS SDK metrics snapshot"
  check_pattern "pisces-sdk-js/test/pisces-sdk.test.js" \
    'stale|retry|metrics|configVersionLongPollMillis' "JS SDK runtime resilience tests"
}

check_observability_markers() {
  check_pattern "docs/observability/prometheus/pisces-runtime-plane-alerts.yml" \
    'pisces_traffic_assignment_requests_total' "traffic assignment alert metric"
  check_pattern "docs/observability/prometheus/pisces-runtime-plane-alerts.yml" \
    'pisces_config_change_broadcast_published_total' "config broadcast publish alert metric"
  check_pattern "docs/observability/grafana/pisces-runtime-plane-dashboard.json" \
    'pisces_traffic_assignment_latency_seconds' "traffic latency dashboard metric"
  check_pattern "pisces-service/src/main/java/com/pisces/service/metrics/EventReplayMetrics.java" \
    'pisces\.event\.replay\.jobs' "event replay worker job metric"
  check_pattern "docs/observability/README.md" \
    'pisces_event_replay_jobs_total' "event replay worker metrics documented"
  check_pattern "docs/observability/runtime-plane-runbook.md" \
    'runtime-plane-release-package-check.sh' "release package check linked from runbook"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-release-package-check.sh' "release package check linked from checklist"
  check_pattern "docs/operations/runtime-config-contract-matrix.md" \
    'RuntimeConfigControllerContractTest' "HTTP contract test evidence in matrix"
}

check_ci_markers() {
  check_pattern ".github/workflows/runtime-plane-release-package.yml" \
    'PISCES_RELEASE_PACKAGE_RUN_TESTS:[[:space:]]*['\''"]true['\''"]' "CI runs focused release package tests"
  check_pattern ".github/workflows/runtime-plane-release-package.yml" \
    'PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL:[[:space:]]*['\''"]true['\''"]' "CI requires promtool semantic checks"
  check_pattern ".github/workflows/runtime-plane-release-package.yml" \
    'actions/upload-artifact@v4' "CI uploads release package report"
  check_pattern ".github/workflows/runtime-plane-release-package.yml" \
    'scripts/event-pipeline-replay-audit\.sh' "CI triggers on event pipeline replay audit script changes"
  check_pattern "config/pisces-local.env.example" \
    'TONGYI_API_KEY.*local-qianwen-api-key' "local env template uses Qianwen API key placeholder"
  check_pattern "config/pisces-local.env.example" \
    'TONGYI_MODEL="qwen3\.7-max"' "local env template uses production TongYi text model"
  check_pattern "config/pisces-local.env.example" \
    'TONGYI_API_MODE="dashscope"' "local env template uses DashScope text API mode"
  check_pattern "config/pisces-local.env.example" \
    'TONGYI_FALLBACK_MODEL="qwen3\.7-max"' "local env template keeps stable TongYi fallback model"
  check_pattern "config/pisces-local.env.example" \
    'export PISCES_API_KEY_SPECS=' "local env template defines scoped API keys"
  check_pattern "scripts/production-infrastructure-local-bootstrap.sh" \
    'pisces-production-infrastructure-local-bootstrap' "production infrastructure local bootstrap summary contract"
  check_pattern "scripts/production-infrastructure-local-bootstrap.sh" \
    'NEEDS_QIANWEN_API_KEY' "production infrastructure local bootstrap detects missing Qianwen key"
  check_pattern "scripts/production-infrastructure-local-bootstrap.sh" \
    'READY_TO_SOURCE' "production infrastructure local bootstrap confirms source-ready env"
  check_pattern "scripts/production-infrastructure-local-bootstrap.sh" \
    'replace only' "production infrastructure local bootstrap preserves single-key setup"
  check_pattern "scripts/production-infrastructure-local-bootstrap.sh" \
    'production-infrastructure-local-finalize.sh' "production infrastructure local bootstrap points to one-shot finalizer"
  check_pattern "scripts/production-infrastructure-local-bootstrap-smoke-test.sh" \
    'production infrastructure local bootstrap smoke test passed' "production infrastructure local bootstrap behavior smoke test"
  check_pattern "compose.local.yml" \
    'mysql:8\.0' "local dependency compose includes MySQL"
  check_pattern "compose.local.yml" \
    'redis:7\.2-alpine' "local dependency compose includes Redis"
  check_pattern "compose.local.yml" \
    'zookeeper:3\.9' "local dependency compose includes Zookeeper"
  check_pattern "scripts/production-infrastructure-local-dependency-stack.sh" \
    'pisces-production-infrastructure-local-dependency-stack' "production infrastructure local dependency stack summary contract"
  check_pattern "scripts/production-infrastructure-local-dependency-stack.sh" \
    'PISCES_LOCAL_STACK_AUTO_PORTS' "production infrastructure local dependency stack auto ports contract"
  check_pattern "scripts/production-infrastructure-local-dependency-stack.sh" \
    'config/pisces-local-stack.env' "production infrastructure local dependency stack generated env contract"
  check_pattern "scripts/production-infrastructure-local-dependency-stack.sh" \
    'PISCES_REDIS_DOCKER_CONTAINER' "production infrastructure local dependency stack exports Redis Docker container"
  check_pattern "scripts/production-infrastructure-local-dependency-stack-smoke-test.sh" \
    'production infrastructure local dependency stack smoke test passed' "production infrastructure local dependency stack behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-dependency-check.sh" \
    'pisces-production-infrastructure-local-dependency-check' "production infrastructure local dependency check summary contract"
  check_pattern "scripts/production-infrastructure-local-dependency-check.sh" \
    'READY_FOR_LOCAL_SERVICE_START' "production infrastructure local dependency check ready status"
  check_pattern "scripts/production-infrastructure-local-dependency-check.sh" \
    'NEEDS_LOCAL_DEPENDENCIES' "production infrastructure local dependency check hold status"
  check_pattern "scripts/production-infrastructure-local-dependency-check.sh" \
    'mysql schema tables present' "production infrastructure local dependency check validates MySQL schema"
  check_pattern "scripts/production-infrastructure-local-dependency-check.sh" \
    'redis ping succeeds' "production infrastructure local dependency check validates Redis ping"
  check_pattern "scripts/production-infrastructure-local-dependency-check-smoke-test.sh" \
    'production infrastructure local dependency check smoke test passed' "production infrastructure local dependency check behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-mysql-schema-apply.sh" \
    'pisces-production-infrastructure-local-mysql-schema-apply' "production infrastructure local MySQL schema apply summary contract"
  check_pattern "scripts/production-infrastructure-local-mysql-schema-apply.sh" \
    'PLAN_ONLY' "production infrastructure local MySQL schema apply dry-run mode"
  check_pattern "scripts/production-infrastructure-local-mysql-schema-apply.sh" \
    'REFUSED_NONLOCAL_MYSQL' "production infrastructure local MySQL schema apply refuses nonlocal targets"
  check_pattern "scripts/production-infrastructure-local-mysql-schema-apply.sh" \
    'skippedMigrationFiles' "production infrastructure local MySQL schema apply skips migrations by default"
  check_pattern "scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh" \
    'production infrastructure local mysql schema apply smoke test passed' "production infrastructure local MySQL schema apply behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-service.sh" \
    'pisces-production-infrastructure-local-service' "production infrastructure local service summary contract"
  check_pattern "scripts/production-infrastructure-local-service.sh" \
    'NEEDS_QIANWEN_API_KEY' "production infrastructure local service refuses missing Qianwen key"
  check_pattern "scripts/production-infrastructure-local-service.sh" \
    'actuator/health' "production infrastructure local service polls actuator health"
  check_pattern "scripts/production-infrastructure-local-service.sh" \
    'java -jar' "production infrastructure local service starts backend jar"
  check_pattern "scripts/production-infrastructure-local-service-smoke-test.sh" \
    'production infrastructure local service smoke test passed' "production infrastructure local service behavior smoke test"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'PISCES_ZOOKEEPER_CONNECT_STRING' "application runtime accepts local Zookeeper stack override"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'TONGYI_MODEL:qwen3\.7-max' "application runtime defaults to production TongYi model with override"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'TONGYI_FALLBACK_MODEL:qwen3\.7-max' "application runtime keeps stable TongYi fallback model"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'TONGYI_API_MODE:dashscope' "application runtime defaults to DashScope API mode"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'pisces-production-infrastructure-local-ai-smoke' "production infrastructure local AI smoke summary contract"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'variants/generate' "production infrastructure local AI smoke calls variant generation"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'TONGYI_MODEL' "production infrastructure local AI smoke records TongYi model env"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'qwen3\.7-max' "production infrastructure local AI smoke defaults to production TongYi model"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'TONGYI_FALLBACK_MODEL' "production infrastructure local AI smoke records fallback model env"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'production-dashscope-qwen3\.7-max-with-token-plan-preview-opt-in' "production infrastructure local AI smoke records production model strategy"
  check_pattern "scripts/production-infrastructure-local-ai-smoke.sh" \
    'tongyiSelectedModel' "production infrastructure local AI smoke records actual selected model"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/VariantCandidateGenerateResponse.java" \
    'aiModel' "variant generation response exposes actual AI model"
  check_pattern "pisces-api/src/main/java/com/pisces/api/variant/VariantController.java" \
    'getLastTextGenerationMetadata' "variant generation controller attaches AI model metadata"
  check_pattern "../pisces-web/src/pages/VariantGenerator.jsx" \
    'normalizeVariantGenerationModelEvidence' "variant generation UI displays AI model metadata"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    '09-variant-lab-tongyi-model-evidence' "core screenshots include variant generation AI model evidence"
  check_pattern "scripts/production-infrastructure-local-ai-smoke-smoke-test.sh" \
    'production infrastructure local AI smoke smoke test passed' "production infrastructure local AI smoke behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'pisces-production-infrastructure-local-evidence-collect' "production infrastructure local evidence collector summary contract"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'runtime-plane-capacity-baseline.sh' "production infrastructure local evidence collector runs capacity baseline"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'event-pipeline-replay-audit.sh' "production infrastructure local evidence collector runs event replay audit"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'PISCES_LOCAL_COLLECT_AUTO_DEMO' "production infrastructure local evidence collector can auto-create demo experiments"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'experiments/generator/demo' "production infrastructure local evidence collector uses local demo generator"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'PISCES_LOCAL_COLLECT_RUN_CLOSEOUT' "production infrastructure local evidence collector can run final local closeout"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'closeoutWrapper' "production infrastructure local evidence collector exposes closeout wrapper"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'PISCES_LOCAL_SERVICE_SUMMARY_FILE' "production infrastructure local evidence collector records local service summary"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'PISCES_LOCAL_COLLECT_REQUIRE_SERVICE_SUMMARY' "production infrastructure local evidence collector gates on local service summary"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'production-infrastructure-local-service.sh start' "production infrastructure local evidence collector points to service start"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'PISCES_LOCAL_ENV_FILE' "production infrastructure local evidence collector loads local env file"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'redisFault' "production infrastructure local evidence collector records Redis fault mode"
  check_pattern "scripts/production-infrastructure-local-evidence-collect.sh" \
    'screenshotDir' "production infrastructure local evidence collector records frontend screenshots"
  check_pattern "scripts/production-infrastructure-local-evidence-collect-smoke-test.sh" \
    'PISCES_LOCAL_COLLECT_REDIS_FAULT_MODE=docker-stop' "production infrastructure local evidence collector docker Redis fault plan smoke"
  check_pattern "scripts/production-infrastructure-local-evidence-collect-smoke-test.sh" \
    'production infrastructure local evidence collect smoke test passed' "production infrastructure local evidence collector behavior smoke test"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'pisces-production-infrastructure-completion-audit' "production infrastructure completion audit summary contract"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'completionStatus' "production infrastructure completion audit exposes completion status"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'sampledColorCount' "production infrastructure completion audit validates screenshot visual content"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'width>=1366, height>=768, landscape' "production infrastructure completion audit requires landscape screenshots"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'core frontend layout audit status' "production infrastructure completion audit requires frontend layout audit PASS"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'layout-audit.json' "production infrastructure completion audit reads frontend layout audit file"
  check_pattern "scripts/production-infrastructure-completion-audit.sh" \
    'pisces-web-core-layout-audit' "production infrastructure completion audit validates layout audit contract"
  check_pattern "../pisces-web/package.json" \
    'capture:core' "frontend exposes stable core screenshot capture command"
  check_pattern "../pisces-web/package.json" \
    'audit:core-layout' "frontend exposes strict core layout audit command"
  check_pattern "../pisces-web/package.json" \
    'audit:prod-high' "frontend exposes production high-severity audit command"
  check_pattern "../pisces-web/package.json" \
    'playwright' "frontend declares Playwright for core screenshot capture"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    'PISCES_WEB_SCREENSHOT_DIR' "frontend core screenshot capture supports configurable output directory"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    'PISCES_WEB_LAYOUT_AUDIT_FILE' "frontend core screenshot capture writes layout audit summary"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    'pisces-web-core-layout-audit' "frontend core screenshot capture exposes layout audit contract"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    'HORIZONTAL_WORKSPACE' "frontend core screenshot capture enforces horizontal workspace fit"
  check_pattern "../pisces-web/scripts/capture-core-functions.cjs" \
    '03e-experiment-statistics-mab.png' "frontend core screenshot capture covers experiment statistics"
  check_pattern "scripts/production-infrastructure-completion-audit-smoke-test.sh" \
    'pisces-web-core-layout-audit' "production infrastructure completion audit smoke covers layout audit"
  check_pattern "scripts/production-infrastructure-completion-audit-smoke-test.sh" \
    'production infrastructure completion audit smoke test passed' "production infrastructure completion audit behavior smoke test"
  check_pattern "scripts/production-infrastructure-closeout.sh" \
    'pisces-production-infrastructure-closeout' "production infrastructure closeout report contract"
  check_pattern "scripts/production-infrastructure-local-evidence-workspace.sh" \
    'Local evidence workspace prepared' "production infrastructure local evidence workspace helper"
  check_pattern "scripts/production-infrastructure-local-evidence-workspace-smoke-test.sh" \
    'production infrastructure local evidence workspace smoke test passed' "production infrastructure local evidence workspace behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-evidence-validate.sh" \
    'pisces-production-infrastructure-local-evidence-validate' "production infrastructure local evidence validator summary contract"
  check_pattern "scripts/production-infrastructure-local-evidence-validate.sh" \
    'replayPlanAfterRepair' "production infrastructure local evidence validator accepts raw event replay audit summaries"
  check_pattern "scripts/production-infrastructure-local-evidence-validate-smoke-test.sh" \
    'production infrastructure local evidence validate smoke test passed' "production infrastructure local evidence validator behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    'pisces-production-infrastructure-local-frontend-evidence' "production infrastructure local frontend evidence summary contract"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    'npm run audit:prod-high' "production infrastructure local frontend evidence runs frontend high audit"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    'npm run capture:core' "production infrastructure local frontend evidence captures screenshots"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    'layoutAudit' "production infrastructure local frontend evidence records layout audit"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    'requiredScreenshots' "production infrastructure local frontend evidence records required screenshot gates"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence.sh" \
    '09-variant-lab-tongyi-model-evidence' "production infrastructure local frontend evidence requires variant model evidence screenshot"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh" \
    'PISCES_WEB_LAYOUT_AUDIT_FILE' "production infrastructure local frontend evidence smoke covers layout audit"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh" \
    '09-variant-lab-tongyi-model-evidence' "production infrastructure local frontend evidence smoke covers variant model evidence screenshot"
  check_pattern "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh" \
    'production infrastructure local frontend evidence smoke test passed' "production infrastructure local frontend evidence behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'pisces-production-infrastructure-local-prekey' "production infrastructure local prekey summary contract"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'READY_FOR_API_KEY' "production infrastructure local prekey exposes ready-for-key state"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'PISCES_LOCAL_FINALIZE_DRY_RUN=true' "production infrastructure local prekey rehearses finalizer dry run"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'production-infrastructure-local-ai-smoke.sh' "production infrastructure local prekey verifies AI smoke plan"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'production-infrastructure-local-evidence-collect.sh' "production infrastructure local prekey verifies evidence collection plan"
  check_pattern "scripts/production-infrastructure-local-prekey-check.sh" \
    'frontendEvidence' "production infrastructure local prekey exposes frontend evidence gates"
  check_pattern "scripts/production-infrastructure-local-prekey-check-smoke-test.sh" \
    '09-variant-lab-tongyi-model-evidence' "production infrastructure local prekey smoke covers variant model evidence screenshot gate"
  check_pattern "scripts/production-infrastructure-local-prekey-check-smoke-test.sh" \
    'production infrastructure local prekey check smoke test passed' "production infrastructure local prekey behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'pisces-production-infrastructure-local-completion-verify' "production infrastructure local completion verify summary contract"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'NEEDS_API_KEY' "production infrastructure local completion verify exposes needs-key state"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'NEEDS_FINALIZER' "production infrastructure local completion verify exposes needs-finalizer state"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'completionStatus.*COMPLETE|COMPLETE' "production infrastructure local completion verify requires complete closeout"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'releaseEvidenceManifest' "production infrastructure local completion verify checks evidence manifest"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'local AI smoke status' "production infrastructure local completion verify requires AI smoke PASS"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'aiSmokeSummary' "production infrastructure local completion verify exposes AI smoke evidence"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'core frontend layout audit status' "production infrastructure local completion verify requires layout audit PASS"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'layoutAudit' "production infrastructure local completion verify exposes layout audit evidence"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'variant lab model evidence screenshot exists' "production infrastructure local completion verify requires variant model evidence screenshot"
  check_pattern "scripts/production-infrastructure-local-completion-verify.sh" \
    'variant lab model evidence layout audit passed' "production infrastructure local completion verify requires variant model evidence layout audit PASS"
  check_pattern "scripts/production-infrastructure-local-completion-verify-smoke-test.sh" \
    'pisces-web-core-layout-audit' "production infrastructure local completion verify smoke covers layout audit"
  check_pattern "scripts/production-infrastructure-local-completion-verify-smoke-test.sh" \
    '09-variant-lab-tongyi-model-evidence' "production infrastructure local completion verify smoke covers variant model evidence screenshot"
  check_pattern "scripts/production-infrastructure-local-completion-verify-smoke-test.sh" \
    'pisces-production-infrastructure-local-ai-smoke' "production infrastructure local completion verify smoke covers AI smoke"
  check_pattern "scripts/production-infrastructure-local-completion-verify-smoke-test.sh" \
    'production infrastructure local completion verify smoke test passed' "production infrastructure local completion verify behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'pisces-production-infrastructure-local-finalize' "production infrastructure local finalize summary contract"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'NEEDS_QIANWEN_API_KEY' "production infrastructure local finalize refuses missing Qianwen key"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FINALIZE_BOOTSTRAP_ENV' "production infrastructure local finalize bootstraps missing env"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FINALIZE_START_DEPENDENCY_STACK' "production infrastructure local finalize can start dependency stack"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-dependency-stack.sh' "production infrastructure local finalize starts local dependency stack"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-mysql-schema-apply.sh' "production infrastructure local finalize applies local MySQL schema"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-dependency-check.sh' "production infrastructure local finalize runs local dependency check"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-service.sh' "production infrastructure local finalize starts local service"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-readiness.sh' "production infrastructure local finalize runs readiness"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FINALIZE_RUN_AI_SMOKE' "production infrastructure local finalize can run AI smoke"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-ai-smoke.sh' "production infrastructure local finalize runs AI smoke"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-frontend-evidence.sh' "production infrastructure local finalize captures frontend evidence"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'frontendEvidence' "production infrastructure local finalize exposes frontend evidence gates"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FRONTEND_REQUIRED_SCREENSHOTS' "production infrastructure local finalize passes required frontend screenshots"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-evidence-collect.sh' "production infrastructure local finalize collects evidence"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_COLLECT_RUN_CLOSEOUT' "production infrastructure local finalize can run final closeout"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FINALIZE_RUN_COMPLETION_VERIFY' "production infrastructure local finalize can run completion verify"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'production-infrastructure-local-completion-verify.sh' "production infrastructure local finalize verifies final completion"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_LOCAL_FINALIZE_REDIS_FAULT_MODE' "production infrastructure local finalize resolves Redis fault mode"
  check_pattern "scripts/production-infrastructure-local-finalize.sh" \
    'PISCES_REDIS_DOCKER_CONTAINER' "production infrastructure local finalize passes Redis container to collector"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    'effectiveMode.*docker-stop|docker-stop' "production infrastructure local finalize auto docker Redis fault smoke"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    'envCreatedByFinalize' "production infrastructure local finalize smoke covers missing env bootstrap"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    'runCompletionVerify' "production infrastructure local finalize smoke checks completion verify default"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    '09-variant-lab-tongyi-model-evidence' "production infrastructure local finalize smoke covers variant model evidence screenshot gate"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    'runAiSmoke' "production infrastructure local finalize smoke checks AI smoke default"
  check_pattern "scripts/production-infrastructure-local-finalize-smoke-test.sh" \
    'production infrastructure local finalize smoke test passed' "production infrastructure local finalize behavior smoke test"
  check_pattern "scripts/production-infrastructure-local-closeout.sh" \
    'PISCES_COMPLETION_TARGET_ENVIRONMENT=local' "production infrastructure local closeout targets local environment"
  check_pattern "scripts/production-infrastructure-local-closeout.sh" \
    'production-infrastructure-local-evidence-validate.sh' "production infrastructure local closeout validates evidence structure"
  check_pattern "scripts/production-infrastructure-local-closeout.sh" \
    'Local closeout evidence still contains TODO placeholders' "production infrastructure local closeout rejects placeholder evidence"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'pisces-production-infrastructure-local-readiness' "production infrastructure local readiness summary contract"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'dependencyCheck' "production infrastructure local readiness includes dependency preflight"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'PISCES_QIANWEN_API_KEY_STATUS' "production infrastructure local readiness rejects placeholder Qianwen keys"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'qianwenApiKey' "production infrastructure local readiness exposes Qianwen key status"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'localEnv' "production infrastructure local readiness exposes local env file status"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'keySetupStage' "production infrastructure local readiness exposes key setup stage"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'config/pisces-local.env.example' "production infrastructure local readiness points to env template"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'local service health' "production infrastructure local readiness checks local service health"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'collector plan-only preflight' "production infrastructure local readiness checks collector plan"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'localService' "production infrastructure local readiness exposes collector service summary gate"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'PISCES_LOCAL_ENV_FILE' "production infrastructure local readiness loads local env file"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'finalizeCommand' "production infrastructure local readiness exposes finalize command"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'nextCommands' "production infrastructure local readiness exposes direct next commands"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'core screenshot evidence directory' "production infrastructure local readiness checks screenshots"
  check_pattern "scripts/production-infrastructure-local-readiness.sh" \
    'core screenshot layout audit' "production infrastructure local readiness checks screenshot layout audit"
  check_pattern "scripts/production-infrastructure-secret-scan.sh" \
    'pisces-production-infrastructure-secret-scan' "production infrastructure secret scan summary contract"
  check_pattern "scripts/production-infrastructure-secret-scan.sh" \
    'skippedLocalEnvFiles' "production infrastructure secret scan skips ignored local env files"
  check_pattern "scripts/production-infrastructure-completion-audit-smoke-test.sh" \
    'production infrastructure closeout smoke test passed' "production infrastructure closeout behavior smoke test"
  check_pattern "docs/operations/production-infrastructure-completion-audit.md" \
    'completionStatus=COMPLETE' "production infrastructure completion criteria documented"
  check_pattern "docs/operations/runtime-plane-release-package-check.md" \
    'PISCES_RELEASE_PACKAGE_REPORT_FILE' "release package report documented"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Release Package Report' "preprod drill record references package report"
  check_pattern "scripts/runtime-plane-preprod-drill-record-check.sh" \
    'pisces-runtime-plane-preprod-drill-record-check' "preprod drill record check summary contract"
  check_pattern "scripts/runtime-plane-preprod-drill-record-check.sh" \
    'PISCES_PREPROD_REQUIRE_EVENT_REPLAY' "preprod drill record check can require event replay evidence"
  check_pattern "scripts/runtime-plane-preprod-drill-record-smoke-test.sh" \
    'runtime plane preprod drill record smoke test passed' "preprod drill record behavior smoke test"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-sample.md" \
    'Preprod record check status[[:space:]]*\|[[:space:]]*PASS' "preprod drill record sample captures check result"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-preprod-drill-record-check.sh' "preprod drill record check linked from checklist"
  check_pattern "docs/operations/runtime-plane-release-evidence-archive.md" \
    'PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE' "release evidence compare manifest documented"
  check_pattern "scripts/runtime-plane-release-evidence-archive.sh" \
    'PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE' "release evidence archive accepts event replay audit summary"
  check_pattern "scripts/runtime-plane-release-evidence-archive.sh" \
    'eventPipelineReplayAudit' "release evidence manifest captures event replay audit summary"
  check_pattern "scripts/runtime-plane-release-evidence-archive.sh" \
    'segmentSummary' "release evidence manifest captures replay segment summary"
  check_pattern "scripts/runtime-plane-release-evidence-archive.sh" \
    'Segmented repair audit must include replayPlan.segments' "release evidence archive validates segmented repair evidence"
  check_pattern "scripts/runtime-plane-release-evidence-archive-smoke-test.sh" \
    'release evidence archive event replay audit smoke test passed' "release evidence archive event replay audit behavior smoke test"
  check_pattern "scripts/runtime-plane-release-evidence-archive-smoke-test.sh" \
    'maxSegmentUnmaterializedCountBefore' "release evidence smoke checks segmented replay summary"
  check_pattern "scripts/runtime-plane-release-evidence-strict-smoke-test.sh" \
    'release evidence archive strict CI smoke test passed' "release evidence archive strict CI behavior smoke test"
  check_pattern "scripts/runtime-plane-release-evidence-strict-smoke-test.sh" \
    'PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true' "release evidence strict smoke enforces clean CI package report"
  check_pattern "scripts/runtime-plane-release-evidence-strict-smoke-test.sh" \
    'requirePromtool' "release evidence strict smoke preserves promtool requirement"
  check_pattern "docs/operations/runtime-plane-release-evidence-archive.md" \
    'PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE' "release evidence docs mention event replay audit summary"
  check_pattern "docs/operations/runtime-plane-release-evidence-archive.md" \
    'segmentSummary' "release evidence docs mention replay segment summary"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-release-evidence-archive.sh' "release evidence archive linked from checklist"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'PISCES_RELEASE_PACKAGE_RUN_TESTS=true' "release checklist requires strict CI package report"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-post-release-slo-review.sh' "post-release SLO review linked from checklist"
  check_pattern "docs/operations/runtime-plane-post-release-slo-review.md" \
    'PISCES_POST_RELEASE_MAX_P95_BASELINE_RATIO' "post-release SLO thresholds documented"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-experiment-impact-sampling.sh' "experiment impact sampling linked from checklist"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-post-release-incident-review-template.md' "post-release incident review linked from checklist"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Experiment Impact Sampling Summary' "preprod record captures experiment impact sampling"
  check_pattern "docs/operations/runtime-plane-experiment-impact-sampling.md" \
    'PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS' "impact sampling expected version documented"
  check_pattern "docs/operations/runtime-plane-experiment-impact-sampling.md" \
    'PISCES_IMPACT_TRACE_ENABLED' "impact sampling trace safety documented"
  check_pattern "docs/operations/runtime-plane-post-release-incident-review-template.md" \
    'Experiment Impact Sampling Summary' "incident review captures impact sampling evidence"
  check_pattern "docs/observability/runtime-plane-runbook.md" \
    'runtime-plane-experiment-impact-sampling.sh' "impact sampling linked from runbook"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-staged-rollout-decision.sh' "staged rollout decision linked from checklist"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Staged Rollout Decision Summary' "preprod record captures staged rollout decision"
  check_pattern "docs/operations/runtime-plane-staged-rollout-decision.md" \
    'PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE' "staged rollout acceptance record documented"
  check_pattern "docs/operations/runtime-plane-staged-rollout-decision.md" \
    'ROLLBACK' "staged rollout rollback decision documented"
  check_pattern "scripts/runtime-plane-production-acceptance-check.sh" \
    'PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE' "production acceptance consumes staged rollout decision"
  check_pattern "scripts/runtime-plane-production-acceptance-check.sh" \
    'pisces-runtime-plane-production-acceptance-check' "production acceptance summary contract"
  check_pattern "scripts/runtime-plane-experiment-impact-sampling.sh" \
    '"environment": os.environ\["PISCES_ENVIRONMENT"\]' "impact sampling writes environment evidence"
  check_pattern "scripts/runtime-plane-experiment-impact-sampling.sh" \
    '"releaseId": os.environ\["PISCES_RELEASE_ID"\] or None' "impact sampling writes release ID evidence"
  check_pattern "scripts/runtime-plane-production-acceptance-smoke-test.sh" \
    'runtime plane production acceptance smoke test passed' "production acceptance behavior smoke test"
  check_pattern "docs/operations/runtime-plane-production-acceptance.md" \
    'ACCEPT' "production acceptance final decision documented"
  check_pattern "docs/operations/runtime-plane-production-acceptance-sample.json" \
    '"recordType":[[:space:]]*"pisces-runtime-plane-production-acceptance"' "production acceptance sample record type"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'runtime-plane-production-acceptance-check.sh' "production acceptance linked from checklist"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Production Acceptance Summary' "preprod record captures production acceptance summary"
  check_pattern "docs/operations/runtime-plane-rollback-decision-drill-template.md" \
    'PISCES_RELEASE_STAGE' "rollback decision drill command documented"
  check_pattern "docs/observability/runtime-plane-runbook.md" \
    'runtime-plane-staged-rollout-decision.sh' "staged rollout decision linked from runbook"
  check_pattern "docs/operations/runtime-plane-release-checklist.md" \
    'event-pipeline-replay-audit.sh' "event pipeline replay audit linked from checklist"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Event Pipeline Replay Audit Summary' "preprod record captures event pipeline replay audit"
  check_pattern "docs/operations/runtime-plane-preprod-drill-record-template.md" \
    'Repair segment index' "preprod record captures segmented replay repair evidence"
  check_pattern "docs/operations/runtime-plane-redis-fault-injection.md" \
    'PISCES_EVENT_REPLAY_SEGMENT_COUNT' "redis fault drill documents segmented replay audit evidence"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_EXECUTE' "event replay execute flag documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'after_pipeline_healthy' "event replay health gate documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'REPLAY_DERIVED' "event replay operation contract documented"
}

check_event_pipeline_replay_repair_markers() {
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION' "event replay repair flag wired in script"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_EVENT_TYPES' "event replay audit supports scoped event type plans"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_SEGMENT_COUNT' "event replay audit supports segmented replay plans"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX' "event replay audit supports segmented repair execution"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'build_replay_scope_request' "event replay audit builds shared scope request body"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'replayScopeRequest' "event replay audit records scope request in summary"
  check_pattern "scripts/event-pipeline-replay-audit-scope-smoke-test.sh" \
    'event replay audit scoped request smoke test passed' "event replay audit scope behavior smoke test"
  check_pattern "scripts/event-pipeline-replay-segment-repair-smoke-test.sh" \
    'event replay segmented repair smoke test passed' "event replay segmented repair behavior smoke test"
  check_pattern "scripts/event-pipeline-replay-segment-repair-smoke-test.sh" \
    'PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX' "event replay segmented repair smoke executes segment recovery"
  check_pattern "scripts/event-pipeline-replay-segment-repair-smoke-test.sh" \
    'maxSegmentUnmaterializedCount' "event replay segmented repair smoke verifies segment gap closure"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'events/replay/materialization/repair' "event replay repair endpoint wired in script"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'replay_plan_segments_generated' "event replay audit gates segmented plan generation"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'repair_materialization_operation_success' "event replay repair operation gate"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'post_repair_replay_plan_unmaterialized_count' "post-repair materialization coverage gate"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'replayPlanAfterRepair' "event replay repair summary includes post-repair plan"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'repairMaterializationOperation' "event replay repair summary includes operation result"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_JOB_TIMEOUT_SECONDS' "event replay audit polls async replay job terminal status"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'replay_job_terminal_success' "event replay audit gates async replay job terminal success"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'replayJobPollSummary' "event replay audit records replay job poll progress summary"
  check_pattern "scripts/event-pipeline-replay-audit.sh" \
    'PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN' "event replay audit gates plan affected fact count"
  check_pattern "scripts/event-pipeline-replay-audit-scope-smoke-test.sh" \
    'replay_plan_affected_count' "event replay audit smoke test covers affected count gate"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java" \
    'copyReplayDerivedData' "event replay supports filtered derived copy replay"
  check_pattern "pisces-service/src/main/java/com/pisces/service/event/EventMaterializationRecord.java" \
    'REPLAY_COPY' "filtered copy replay records materialization source"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'FILTERED_DERIVED_COPY_REPLAY' "filtered copy replay mode is exposed in replay jobs"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'eventPipelineReplayMaxFilteredCopyFacts' "filtered copy replay has server-side affected fact limit"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS' "filtered copy replay limit is externally configurable"
  check_pattern "pisces-service/src/main/resources/application.yml" \
    'PISCES_EVENT_REPLAY_BATCH_SIZE' "event replay batch size is externally configurable"
  check_pattern "scripts/runtime-plane-release-package-check.sh" \
    'AnalysisServiceImplEventPipelineTest,EventInboxMaterializerTest,ProductionExperimentFlowSmokeTest,EventReplayMetricsTest,EventReplayExecutorConfigTest' "release package focused tests include event replay governance"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'repairUnmaterializedDerivedData' "event replay repair uses local materialization path for filtered plans"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'EventReplayProgressReporter' "event replay worker reports batch progress"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'updateReplayJobProgress' "event replay worker updates running job counters"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'calculateReplayProgressPercent' "event replay worker exposes progress percentage"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'buildReplayPlanSegments' "event replay plan builds time segments"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'repairEventMaterializationSegment' "event replay service supports segmented materialization repair"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'PlannedAffectedCount' "event replay worker persists planned fact counts"
  check_pattern "pisces-api/src/main/java/com/pisces/api/analysis/AnalysisController.java" \
    'events/replay/materialization/repair/segments/\{segmentIndex\}' "event replay segmented repair endpoint wired in API"
  check_pattern "pisces-api/src/test/java/com/pisces/api/analysis/AnalysisControllerEventReplayContractTest.java" \
    'shouldRouteSegmentedMaterializationRepair' "event replay segmented repair API contract test"
  check_pattern "pisces-common/src/main/java/com/pisces/common/request/EventReplayPlanRequest.java" \
    'segmentCount' "event replay plan request exposes segment count"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/EventReplayPlanResponse.java" \
    'ReplayPlanSegment' "event replay plan response exposes segment rows"
  check_pattern "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_scope_index_migration.sql" \
    'idx_event_replay_scope' "event replay event time-scope index migration"
  check_pattern "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_scope_index_migration.sql" \
    'idx_exposure_replay_scope' "event replay exposure time-scope index migration"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'stopWhenCancellationRequested' "event replay cancellation is gated by rebuild mode"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java" \
    'eventReplayBatchSize' "event replay materializer reads batch size"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java" \
    'EventReplayProgressReporter' "event replay materializer accepts progress reporter"
  check_pattern "pisces-service/src/main/java/com/pisces/service/repository/ExperimentEventRepository.java" \
    'listByReplayScopeBatch' "event replay event repository supports replay-scope batching"
  check_pattern "pisces-service/src/main/java/com/pisces/service/repository/ExperimentExposureRepository.java" \
    'listByReplayScopeBatch' "event replay exposure repository supports replay-scope batching"
  check_pattern "pisces-service/src/main/java/com/pisces/service/repository/EventReplayJobRepository.java" \
    'updateProgress' "event replay repository supports running progress updates"
  check_pattern "pisces-service/src/main/resources/mapper/EventReplayJobMapper.xml" \
    'updateProgress' "event replay job mapper persists running progress updates"
  check_pattern "pisces-service/src/main/resources/mapper/EventReplayJobMapper.xml" \
    'planned_affected_count' "event replay job mapper persists planned progress counts"
  check_pattern "pisces-service/src/main/resources/sql/mysql/pisces_event_replay_job_progress_migration.sql" \
    'planned_affected_count' "event replay planned progress migration"
  check_pattern "pisces-service/src/main/java/com/pisces/service/event/EventReplayProgressReporter.java" \
    'EventReplayProgressReporter' "event replay progress reporter contract"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/EventInboxMaterializer.java" \
    'SOURCE_REPAIR_MATERIALIZATION' "event replay repair records repair materialization source"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'repairEventMaterializationShouldRunLocalRepairWhenFilteredPlanHasGaps' "filtered materialization repair service test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java" \
    'repairUnmaterializedDerivedDataShouldOnlyRefreshLedgerWhenFactAlreadyInStore' "local materialization repair dedupe test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'replayEventPipelineShouldCreateFilteredCopyReplayJob' "filtered copy replay service test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'replayEventPipelineShouldRejectFilteredCopyReplayWhenPlanExceedsLimit' "filtered copy replay limit test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'getPlannedAffectedCount' "event replay planned progress test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'planEventReplayShouldReturnSegmentPlanWhenSegmentCountRequested' "event replay segmented plan service test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'repairEventMaterializationSegmentShouldUseResolvedSegmentScope' "event replay segmented repair service test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/AnalysisServiceImplEventPipelineTest.java" \
    'replayEventPipelineShouldFinishFullRebuildBeforeHonoringCancellation' "full replay cancellation safe point test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java" \
    'copyReplayDerivedDataShouldNotDuplicateExistingDerivedFacts' "filtered copy replay dedupe test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java" \
    'copyReplayDerivedDataShouldProcessScopedFactsInBatches' "filtered copy replay batch pagination test"
  check_pattern "pisces-service/src/test/java/com/pisces/service/service/impl/EventInboxMaterializerTest.java" \
    'repairUnmaterializedDerivedDataShouldKeepFirstPageWhenLedgerSetShrinks' "filtered repair shrinking-set pagination test"
  check_pattern "pisces-api/src/main/java/com/pisces/api/analysis/AnalysisController.java" \
    'events/replay/jobs/\{replayJobId\}/cancel' "event replay job cancel endpoint wired in API"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'CANCEL_REPLAY_JOB' "event replay job cancel operation wired in service"
  check_pattern "pisces-service/src/main/java/com/pisces/service/service/impl/AnalysisServiceImpl.java" \
    'requestCancellation' "event replay job cancel request wired in service"
  check_pattern "pisces-service/src/main/resources/mapper/EventReplayJobMapper.xml" \
    'CANCEL_REQUESTED' "event replay job cancel-requested transition guarded in mapper"
  check_pattern "pisces-service/src/main/resources/mapper/EventReplayJobMapper.xml" \
    'markCancelled' "event replay job cancel transition guarded in mapper"
  check_pattern ".knowledge-base/api-surface.md" \
    '/events/replay/jobs/\{replayJobId\}/cancel' "event replay job cancel contract documented"
  check_pattern ".knowledge-base/api-surface.md" \
    'CANCEL_REQUESTED' "event replay cancel-requested contract documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_REPAIR_MATERIALIZATION' "event replay repair flag documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_EVENT_TYPES' "event replay scoped plan variables documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_SEGMENT_COUNT' "event replay segmented plan variable documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_REPAIR_SEGMENT_INDEX' "event replay segmented repair variable documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'replayScopeRequest' "event replay scoped request evidence documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'post_repair_replay_plan_unmaterialized_count' "post-repair coverage gate documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'REPAIR_MATERIALIZATION' "event replay repair operation contract documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    '复制型 replay' "filtered copy replay operations documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_MAX_AFFECTED_PLAN' "event replay affected count gate documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_MAX_FILTERED_COPY_FACTS' "filtered copy replay server limit documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'PISCES_EVENT_REPLAY_BATCH_SIZE' "event replay batch size documented"
  check_pattern "docs/operations/event-pipeline-replay-audit.md" \
    'replayJobPollSummary' "event replay poll progress evidence documented"
  check_pattern ".knowledge-base/api-surface.md" \
    '运行中计数为批处理安全点累计值' "event replay running progress contract documented"
  check_pattern ".knowledge-base/api-surface.md" \
    'progressPercent' "event replay progress percent contract documented"
  check_pattern ".knowledge-base/api-surface.md" \
    'segmentCount' "event replay segmented plan contract documented"
  check_pattern ".knowledge-base/api-surface.md" \
    '/events/replay/materialization/repair/segments/\{segmentIndex\}' "event replay segmented repair contract documented"
  check_pattern "../pisces-web/src/utils/eventReplayPlan.js" \
    'buildReplayPlanSegmentRows' "frontend normalizes replay plan segment rows"
  check_pattern "../pisces-web/src/components/DataPipelineStatus.jsx" \
    'repair-segment-' "frontend exposes segmented repair action state"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/EventReplayJobResponse.java" \
    '运行中为已处理累计值' "event replay response documents running counters"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/EventReplayJobResponse.java" \
    'plannedAffectedCount' "event replay response exposes planned fact count"
  check_pattern "pisces-common/src/main/java/com/pisces/common/response/EventReplayJobResponse.java" \
    'progressPercent' "event replay response exposes progress percentage"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"repairMaterialization":[[:space:]]*true' "event replay repair sample enables repair"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"repairMaterializationOperation"' "event replay repair sample captures operation"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"replayPlanAfterRepair"' "event replay repair sample captures post-repair plan"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"replayScopeRequest"' "event replay repair sample captures scope request"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"repairSegmentIndex"' "event replay repair sample captures segmented repair index"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"name":[[:space:]]*"replay_plan_segments_generated"' "event replay repair sample has segmented plan gate"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"segments"' "event replay repair sample captures segment plan evidence"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"name":[[:space:]]*"repair_materialization_operation_success"' "event replay repair sample has repair operation gate"
  check_pattern "docs/operations/event-pipeline-replay-audit-sample.json" \
    '"name":[[:space:]]*"post_repair_replay_plan_unmaterialized_count"' "event replay repair sample has post-repair coverage gate"
  check_pattern "docs/operations/runtime-plane-release-package-check.md" \
    'AnalysisServiceImplEventPipelineTest' "release package docs mention event replay governance tests"
}

check_static_assets() {
  local -a bash_scripts=(
    "scripts/production-infrastructure-completion-audit.sh"
    "scripts/production-infrastructure-completion-audit-smoke-test.sh"
    "scripts/production-infrastructure-closeout.sh"
    "scripts/production-infrastructure-local-bootstrap.sh"
    "scripts/production-infrastructure-local-bootstrap-smoke-test.sh"
    "scripts/production-infrastructure-local-dependency-stack.sh"
    "scripts/production-infrastructure-local-dependency-stack-smoke-test.sh"
    "scripts/production-infrastructure-local-dependency-check.sh"
    "scripts/production-infrastructure-local-dependency-check-smoke-test.sh"
    "scripts/production-infrastructure-local-mysql-schema-apply.sh"
    "scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh"
    "scripts/production-infrastructure-local-service.sh"
    "scripts/production-infrastructure-local-service-smoke-test.sh"
    "scripts/production-infrastructure-local-ai-smoke.sh"
    "scripts/production-infrastructure-local-ai-smoke-smoke-test.sh"
    "scripts/production-infrastructure-local-frontend-evidence.sh"
    "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-collect.sh"
    "scripts/production-infrastructure-local-evidence-collect-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-workspace.sh"
    "scripts/production-infrastructure-local-evidence-workspace-smoke-test.sh"
    "scripts/production-infrastructure-local-evidence-validate.sh"
    "scripts/production-infrastructure-local-evidence-validate-smoke-test.sh"
    "scripts/production-infrastructure-local-prekey-check.sh"
    "scripts/production-infrastructure-local-prekey-check-smoke-test.sh"
    "scripts/production-infrastructure-local-completion-verify.sh"
    "scripts/production-infrastructure-local-completion-verify-smoke-test.sh"
    "scripts/production-infrastructure-local-finalize.sh"
    "scripts/production-infrastructure-local-finalize-smoke-test.sh"
    "scripts/production-infrastructure-local-closeout.sh"
    "scripts/production-infrastructure-local-readiness.sh"
    "scripts/production-infrastructure-secret-scan.sh"
    "scripts/runtime-plane-release-drill.sh"
    "scripts/runtime-plane-capacity-baseline.sh"
    "scripts/runtime-plane-archive-baseline.sh"
    "scripts/runtime-plane-redis-fault-injection.sh"
    "scripts/runtime-plane-release-package-check.sh"
    "scripts/runtime-plane-release-evidence-archive.sh"
    "scripts/runtime-plane-release-evidence-archive-smoke-test.sh"
    "scripts/runtime-plane-release-evidence-strict-smoke-test.sh"
    "scripts/runtime-plane-preprod-drill-record-check.sh"
    "scripts/runtime-plane-preprod-drill-record-smoke-test.sh"
    "scripts/runtime-plane-production-acceptance-check.sh"
    "scripts/runtime-plane-production-acceptance-smoke-test.sh"
    "scripts/runtime-plane-post-release-slo-review.sh"
    "scripts/runtime-plane-experiment-impact-sampling.sh"
    "scripts/runtime-plane-staged-rollout-decision.sh"
    "scripts/event-pipeline-replay-audit.sh"
    "scripts/event-pipeline-replay-audit-scope-smoke-test.sh"
    "scripts/event-pipeline-replay-segment-repair-smoke-test.sh"
  )

  local bash_script
  for bash_script in "${bash_scripts[@]}"; do
    check_bash_syntax "$bash_script"
  done

  check_json "docs/observability/grafana/pisces-runtime-plane-dashboard.json"
  check_json "docs/operations/runtime-plane-post-release-slo-sample.json"
  check_json "docs/operations/runtime-plane-staged-rollout-acceptance-sample.json"
  check_json "docs/operations/runtime-plane-production-acceptance-sample.json"
  check_json "docs/operations/event-pipeline-replay-audit-sample.json"
  check_yaml "compose.local.yml"
  check_yaml "docs/observability/prometheus/pisces-runtime-plane-alerts.yml"
  check_promtool_rules "docs/observability/prometheus/pisces-runtime-plane-alerts.yml"
}

check_behavior_smoke_tests() {
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-completion-audit-smoke-test.sh)
  pass "behavior smoke: production infrastructure completion audit"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-bootstrap-smoke-test.sh)
  pass "behavior smoke: production infrastructure local bootstrap"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-dependency-stack-smoke-test.sh)
  pass "behavior smoke: production infrastructure local dependency stack"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-dependency-check-smoke-test.sh)
  pass "behavior smoke: production infrastructure local dependency check"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh)
  pass "behavior smoke: production infrastructure local MySQL schema apply"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-service-smoke-test.sh)
  pass "behavior smoke: production infrastructure local service"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-ai-smoke-smoke-test.sh)
  pass "behavior smoke: production infrastructure local AI smoke"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh)
  pass "behavior smoke: production infrastructure local frontend evidence"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-evidence-collect-smoke-test.sh)
  pass "behavior smoke: production infrastructure local evidence collector"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-evidence-workspace-smoke-test.sh)
  pass "behavior smoke: production infrastructure local evidence workspace"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-evidence-validate-smoke-test.sh)
  pass "behavior smoke: production infrastructure local evidence validator"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-prekey-check-smoke-test.sh)
  pass "behavior smoke: production infrastructure local prekey check"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-completion-verify-smoke-test.sh)
  pass "behavior smoke: production infrastructure local completion verify"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-local-finalize-smoke-test.sh)
  pass "behavior smoke: production infrastructure local finalize"
  (cd "$PISCES_REPO_ROOT" && PISCES_LOCAL_READINESS_CHECK_SERVICE=false \
    PISCES_LOCAL_READINESS_OUTPUT_FILE=target/pisces-production-infrastructure-local-readiness/package-smoke-summary.json \
    bash scripts/production-infrastructure-local-readiness.sh)
  pass "behavior smoke: production infrastructure local readiness"
  (cd "$PISCES_REPO_ROOT" && bash scripts/production-infrastructure-secret-scan.sh)
  pass "behavior smoke: production infrastructure secret scan"
  (cd "$PISCES_REPO_ROOT" && bash scripts/event-pipeline-replay-audit-scope-smoke-test.sh)
  pass "behavior smoke: event replay audit scoped request"
  (cd "$PISCES_REPO_ROOT" && bash scripts/event-pipeline-replay-segment-repair-smoke-test.sh)
  pass "behavior smoke: event replay segmented repair"
  (cd "$PISCES_REPO_ROOT" && bash scripts/runtime-plane-release-evidence-archive-smoke-test.sh)
  pass "behavior smoke: release evidence archive event replay audit"
  (cd "$PISCES_REPO_ROOT" && bash scripts/runtime-plane-release-evidence-strict-smoke-test.sh)
  pass "behavior smoke: release evidence archive strict CI"
  (cd "$PISCES_REPO_ROOT" && bash scripts/runtime-plane-preprod-drill-record-smoke-test.sh)
  pass "behavior smoke: preprod drill record"
  (cd "$PISCES_REPO_ROOT" && bash scripts/runtime-plane-production-acceptance-smoke-test.sh)
  pass "behavior smoke: production acceptance"
}

run_focused_tests() {
  if ! is_true "${PISCES_RELEASE_PACKAGE_RUN_TESTS:-false}"; then
    log "Focused tests skipped; set PISCES_RELEASE_PACKAGE_RUN_TESTS=true to run them."
    return
  fi

  require_command mvn
  require_command npm

  (cd "$PISCES_REPO_ROOT" && mvn -pl pisces-api -am -Dtest=RuntimeConfigControllerContractTest -Dsurefire.failIfNoSpecifiedTests=false test)
  pass "focused test: RuntimeConfigControllerContractTest"
  (cd "$PISCES_REPO_ROOT" && mvn -pl pisces-service -am -Dtest=RuntimeConfigServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test)
  pass "focused test: RuntimeConfigServiceImplTest"
  (cd "$PISCES_REPO_ROOT" && mvn -pl pisces-service -am -Dtest=ExperimentServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test)
  pass "focused test: ExperimentServiceImplTest"
  (cd "$PISCES_REPO_ROOT" && mvn -pl pisces-service -am -Dtest=AnalysisServiceImplEventPipelineTest,EventInboxMaterializerTest,ProductionExperimentFlowSmokeTest,EventReplayMetricsTest,EventReplayExecutorConfigTest -Dsurefire.failIfNoSpecifiedTests=false test)
  pass "focused test: event pipeline replay governance"
  (cd "$PISCES_REPO_ROOT" && mvn -pl pisces-common,pisces-service,pisces-api -am -Dtest=AIDecisionResponseShapeTest,ExperimentDecisionContextBuilderTest,PromptTemplateBuilderTest,AIDecisionServiceImplTest,AnalysisServiceImplAIBridgeTest,AnalysisControllerAIDiagnosisTest,AnalysisControllerAIGraduationDecisionTest -Dsurefire.failIfNoSpecifiedTests=false test)
  pass "focused test: AI decision evidence contract"
  (cd "$PISCES_REPO_ROOT/pisces-sdk-java" && mvn test)
  pass "focused test: pisces-sdk-java"
  (cd "$PISCES_REPO_ROOT/pisces-sdk-js" && npm test -- --run)
  pass "focused test: pisces-sdk-js"
}

write_report() {
  local report_file="$1"
  local finished_at
  finished_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  mkdir -p "$(dirname "$report_file")"
  python3 - "$report_file" "$PISCES_RELEASE_PACKAGE_STARTED_AT" "$finished_at" "$CHECKS_PASSED" "$WARNINGS" <<'PY'
import json
import os
import sys

report_file, started_at, finished_at, checks_passed, warnings = sys.argv[1:6]
report = {
    "reportType": "pisces-runtime-plane-release-package-check",
    "status": "PASS",
    "startedAt": started_at,
    "finishedAt": finished_at,
    "repoRoot": os.environ["PISCES_REPO_ROOT"],
    "gitSha": os.environ["PISCES_GIT_SHA"],
    "gitDirty": os.environ["PISCES_GIT_DIRTY"],
    "checksPassed": int(checks_passed),
    "warnings": int(warnings),
    "runTests": os.environ["PISCES_RELEASE_PACKAGE_RUN_TESTS"],
    "requirePromtool": os.environ["PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL"],
    "requireRuby": os.environ["PISCES_RELEASE_PACKAGE_REQUIRE_RUBY"],
    "tools": {
        "ruby": os.environ["PISCES_RUBY_AVAILABLE"] == "true",
        "promtool": os.environ["PISCES_PROMTOOL_AVAILABLE"] == "true",
        "shellcheck": os.environ["PISCES_SHELLCHECK_AVAILABLE"] == "true",
    },
    "validationCommands": [
        "bash scripts/runtime-plane-release-package-check.sh",
        "bash scripts/production-infrastructure-local-bootstrap-smoke-test.sh",
        "bash scripts/production-infrastructure-local-dependency-stack-smoke-test.sh",
        "bash scripts/production-infrastructure-local-dependency-check-smoke-test.sh",
        "bash scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh",
        "bash scripts/production-infrastructure-local-service-smoke-test.sh",
        "bash scripts/production-infrastructure-local-ai-smoke-smoke-test.sh",
        "bash scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh",
        "bash scripts/production-infrastructure-local-evidence-collect-smoke-test.sh",
        "bash scripts/production-infrastructure-local-evidence-validate-smoke-test.sh",
        "bash scripts/production-infrastructure-local-prekey-check-smoke-test.sh",
        "bash scripts/production-infrastructure-local-completion-verify-smoke-test.sh",
        "bash scripts/production-infrastructure-local-finalize-smoke-test.sh",
        "PISCES_RELEASE_PACKAGE_RUN_TESTS=true bash scripts/runtime-plane-release-package-check.sh",
        "mvn -pl pisces-api -am -Dtest=RuntimeConfigControllerContractTest -Dsurefire.failIfNoSpecifiedTests=false test",
        "mvn -pl pisces-service -am -Dtest=RuntimeConfigServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test",
        "mvn -pl pisces-service -am -Dtest=ExperimentServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test",
        "mvn -pl pisces-service -am -Dtest=AnalysisServiceImplEventPipelineTest,EventInboxMaterializerTest,ProductionExperimentFlowSmokeTest,EventReplayMetricsTest,EventReplayExecutorConfigTest -Dsurefire.failIfNoSpecifiedTests=false test",
        "mvn -pl pisces-common,pisces-service,pisces-api -am -Dtest=AIDecisionResponseShapeTest,ExperimentDecisionContextBuilderTest,PromptTemplateBuilderTest,AIDecisionServiceImplTest,AnalysisServiceImplAIBridgeTest,AnalysisControllerAIDiagnosisTest,AnalysisControllerAIGraduationDecisionTest -Dsurefire.failIfNoSpecifiedTests=false test",
        "mvn -f pisces-sdk-java/pom.xml test",
        "cd pisces-sdk-js && npm test -- --run",
    ],
    "releaseArtifacts": [
        "scripts/production-infrastructure-completion-audit.sh",
        "scripts/production-infrastructure-completion-audit-smoke-test.sh",
        "scripts/production-infrastructure-closeout.sh",
        "compose.local.yml",
        "scripts/production-infrastructure-local-bootstrap.sh",
        "scripts/production-infrastructure-local-bootstrap-smoke-test.sh",
        "scripts/production-infrastructure-local-dependency-stack.sh",
        "scripts/production-infrastructure-local-dependency-stack-smoke-test.sh",
        "scripts/production-infrastructure-local-dependency-check.sh",
        "scripts/production-infrastructure-local-dependency-check-smoke-test.sh",
        "scripts/production-infrastructure-local-mysql-schema-apply.sh",
        "scripts/production-infrastructure-local-mysql-schema-apply-smoke-test.sh",
        "scripts/production-infrastructure-local-service.sh",
        "scripts/production-infrastructure-local-service-smoke-test.sh",
        "scripts/production-infrastructure-local-ai-smoke.sh",
        "scripts/production-infrastructure-local-ai-smoke-smoke-test.sh",
        "scripts/production-infrastructure-local-frontend-evidence.sh",
        "scripts/production-infrastructure-local-frontend-evidence-smoke-test.sh",
        "scripts/production-infrastructure-local-evidence-collect.sh",
        "scripts/production-infrastructure-local-evidence-collect-smoke-test.sh",
        "scripts/production-infrastructure-local-evidence-workspace.sh",
        "scripts/production-infrastructure-local-evidence-workspace-smoke-test.sh",
        "scripts/production-infrastructure-local-evidence-validate.sh",
        "scripts/production-infrastructure-local-evidence-validate-smoke-test.sh",
        "scripts/production-infrastructure-local-prekey-check.sh",
        "scripts/production-infrastructure-local-prekey-check-smoke-test.sh",
        "scripts/production-infrastructure-local-completion-verify.sh",
        "scripts/production-infrastructure-local-completion-verify-smoke-test.sh",
        "config/pisces-local.env.example",
        "scripts/production-infrastructure-local-finalize.sh",
        "scripts/production-infrastructure-local-finalize-smoke-test.sh",
        "scripts/production-infrastructure-local-closeout.sh",
        "scripts/production-infrastructure-local-readiness.sh",
        "scripts/production-infrastructure-secret-scan.sh",
        "scripts/runtime-plane-release-package-check.sh",
        "scripts/runtime-plane-release-evidence-archive.sh",
        "scripts/runtime-plane-release-evidence-archive-smoke-test.sh",
        "scripts/runtime-plane-release-evidence-strict-smoke-test.sh",
        "scripts/runtime-plane-preprod-drill-record-check.sh",
        "scripts/runtime-plane-preprod-drill-record-smoke-test.sh",
        "scripts/runtime-plane-production-acceptance-check.sh",
        "scripts/runtime-plane-production-acceptance-smoke-test.sh",
        "scripts/runtime-plane-post-release-slo-review.sh",
        "scripts/runtime-plane-experiment-impact-sampling.sh",
        "scripts/runtime-plane-staged-rollout-decision.sh",
        "scripts/event-pipeline-replay-audit.sh",
        "docs/operations/production-infrastructure-completion-audit.md",
        "docs/operations/runtime-plane-release-package-check.md",
        "docs/operations/runtime-plane-release-evidence-archive.md",
        "docs/operations/runtime-plane-post-release-slo-review.md",
        "docs/operations/runtime-plane-post-release-slo-sample.json",
        "docs/operations/runtime-plane-experiment-impact-sampling.md",
        "docs/operations/runtime-plane-staged-rollout-decision.md",
        "docs/operations/runtime-plane-staged-rollout-acceptance-sample.json",
        "docs/operations/runtime-plane-production-acceptance.md",
        "docs/operations/runtime-plane-production-acceptance-sample.json",
        "docs/operations/runtime-plane-rollback-decision-drill-template.md",
        "docs/operations/runtime-plane-post-release-incident-review-template.md",
        "docs/operations/event-pipeline-replay-audit.md",
        "docs/operations/event-pipeline-replay-audit-sample.json",
        "docs/operations/runtime-plane-preprod-drill-record-template.md",
        "docs/operations/runtime-plane-preprod-drill-record-sample.md",
        ".github/workflows/runtime-plane-release-package.yml",
    ],
}

with open(report_file, "w", encoding="utf-8") as target:
    json.dump(report, target, ensure_ascii=False, indent=2, sort_keys=True)
    target.write("\n")
PY
  log "OK: report written: $report_file"
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command bash
  require_command grep
  require_command python3

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  export PISCES_REPO_ROOT
  PISCES_RELEASE_PACKAGE_STARTED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  PISCES_RELEASE_PACKAGE_RUN_TESTS="${PISCES_RELEASE_PACKAGE_RUN_TESTS:-false}"
  PISCES_RELEASE_PACKAGE_REPORT_FILE="${PISCES_RELEASE_PACKAGE_REPORT_FILE:-target/pisces-runtime-release-package-check/report.json}"
  PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL="${PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL:-false}"
  PISCES_RELEASE_PACKAGE_REQUIRE_RUBY="${PISCES_RELEASE_PACKAGE_REQUIRE_RUBY:-false}"
  PISCES_GIT_SHA="$(resolve_git_sha)"
  PISCES_GIT_DIRTY="$(resolve_git_dirty)"
  PISCES_RUBY_AVAILABLE="$(command_available ruby)"
  PISCES_PROMTOOL_AVAILABLE="$(command_available promtool)"
  PISCES_SHELLCHECK_AVAILABLE="$(command_available shellcheck)"
  export PISCES_RELEASE_PACKAGE_STARTED_AT
  export PISCES_RELEASE_PACKAGE_RUN_TESTS
  export PISCES_RELEASE_PACKAGE_REPORT_FILE
  export PISCES_RELEASE_PACKAGE_REQUIRE_PROMTOOL
  export PISCES_RELEASE_PACKAGE_REQUIRE_RUBY
  export PISCES_GIT_SHA
  export PISCES_GIT_DIRTY
  export PISCES_RUBY_AVAILABLE
  export PISCES_PROMTOOL_AVAILABLE
  export PISCES_SHELLCHECK_AVAILABLE

  log "Checking runtime plane release package at $PISCES_REPO_ROOT"
  check_required_files
  check_runtime_contract_markers
  check_ai_decision_evidence_markers
  check_manual_conclusion_markers
  check_sdk_contract_markers
  check_observability_markers
  check_ci_markers
  check_event_pipeline_replay_repair_markers
  check_static_assets
  check_behavior_smoke_tests
  run_focused_tests
  write_report "$(resolve_report_file "$PISCES_RELEASE_PACKAGE_REPORT_FILE")"
  log "Release package check passed: checks=${CHECKS_PASSED} warnings=${WARNINGS}"
}

main "$@"
