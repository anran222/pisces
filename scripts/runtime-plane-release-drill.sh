#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_EXPERIMENT_ID=exp_price_001 scripts/runtime-plane-release-drill.sh

Environment:
  PISCES_INSTANCE_URLS                 Comma separated service base URLs. Default: http://localhost:9990/api
  PISCES_EXPERIMENT_ID                 Required experiment ID.
  PISCES_RUNTIME_API_KEY               Runtime scope API key. Default: runtime-key
  PISCES_MANAGEMENT_API_KEY            Management scope API key. Default: ops-key
  PISCES_RELEASE_ACTION                observe | publish-current | save-draft-and-publish. Default: observe
  PISCES_DRAFT_PAYLOAD_FILE            Required when action is save-draft-and-publish.
  PISCES_ASSIGNMENT_REQUESTS           Assignment requests to send. Default: 200
  PISCES_ASSIGNMENT_CONCURRENCY        Parallel assignment workers. Default: 8
  PISCES_VISITOR_PREFIX                Visitor ID prefix. Default: drill-<epoch>
  PISCES_VERSION_WAIT_MILLIS           Runtime version long-poll wait. Default: 25000
  PISCES_CONVERGENCE_TIMEOUT_SECONDS   Version convergence timeout after publish. Default: 60
  PISCES_PUBLISH_OPERATOR              Operator written to publish request. Default: runtime-drill
  PISCES_PUBLISH_COMMENT               Publish comment. Default: runtime plane release drill
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Missing command: $1"
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

normalize_urls() {
  local raw_urls="$1"
  local -a raw_array normalized_array
  IFS=',' read -r -a raw_array <<< "$raw_urls"
  for raw_url in "${raw_array[@]}"; do
    local url
    url="$(trim "$raw_url")"
    [[ -n "$url" ]] || continue
    normalized_array+=("${url%/}")
  done
  [[ "${#normalized_array[@]}" -gt 0 ]] || die "PISCES_INSTANCE_URLS is empty"
  (IFS=','; printf '%s' "${normalized_array[*]}")
}

json_value() {
  local file="$1"
  local path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)

value = payload
for part in sys.argv[2].split("."):
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
    if value is None:
        break

if value is None:
    sys.exit(1)
if isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
PY
}

assert_base_response_success() {
  local file="$1"
  local http_status="$2"
  local url="$3"
  python3 - "$file" "$http_status" "$url" <<'PY'
import json
import sys

file_name, http_status, url = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(file_name, encoding="utf-8") as source:
        payload = json.load(source)
except Exception as exc:
    print(f"Invalid JSON from {url}: {exc}", file=sys.stderr)
    sys.exit(1)

ok_http = http_status.isdigit() and 200 <= int(http_status) < 300
ok_code = payload.get("code") == 200
if not ok_http or not ok_code:
    message = payload.get("message")
    print(f"Request failed: url={url} http={http_status} code={payload.get('code')} message={message}", file=sys.stderr)
    sys.exit(1)
PY
}

request_json() {
  local method="$1"
  local url="$2"
  local api_key="$3"
  local payload_file="$4"
  local output_file="$5"
  local http_status
  if [[ -n "$payload_file" ]]; then
    http_status="$(curl -sS -o "$output_file" -w '%{http_code}' \
      -X "$method" \
      -H "X-Pisces-Api-Key: ${api_key}" \
      -H 'Content-Type: application/json' \
      --data @"$payload_file" \
      "$url" || true)"
  else
    http_status="$(curl -sS -o "$output_file" -w '%{http_code}' \
      -X "$method" \
      -H "X-Pisces-Api-Key: ${api_key}" \
      "$url" || true)"
  fi
  assert_base_response_success "$output_file" "$http_status" "$url"
}

fetch_runtime_config_version() {
  local base_url="$1"
  local output_file="$2"
  request_json GET "${base_url}/runtime/experiments/${PISCES_EXPERIMENT_ID}/config" \
    "$PISCES_RUNTIME_API_KEY" "" "$output_file"
  json_value "$output_file" "data.configVersion"
}

fetch_runtime_version_check() {
  local base_url="$1"
  local known_version="$2"
  local wait_millis="$3"
  local output_file="$4"
  local url="${base_url}/runtime/experiments/${PISCES_EXPERIMENT_ID}/config/version?waitMillis=${wait_millis}"
  if [[ -n "$known_version" ]]; then
    url="${url}&knownVersion=${known_version}"
  fi
  request_json GET "$url" "$PISCES_RUNTIME_API_KEY" "" "$output_file"
  json_value "$output_file" "data.currentVersion"
}

write_publish_payload() {
  local output_file="$1"
  python3 - "$PISCES_PUBLISH_OPERATOR" "$PISCES_PUBLISH_COMMENT" > "$output_file" <<'PY'
import json
import sys

print(json.dumps({
    "operator": sys.argv[1],
    "comment": sys.argv[2],
}, ensure_ascii=False))
PY
}

publish_current_config() {
  local base_url="$1"
  local payload_file="$PISCES_DRILL_TMP/publish.json"
  local output_file="$PISCES_DRILL_TMP/publish-response.json"
  write_publish_payload "$payload_file"
  request_json POST "${base_url}/experiments/${PISCES_EXPERIMENT_ID}/config-versions/publish" \
    "$PISCES_MANAGEMENT_API_KEY" "$payload_file" "$output_file"
  json_value "$output_file" "data.configVersion"
}

publish_config_if_requested() {
  local primary_url="$1"
  case "$PISCES_RELEASE_ACTION" in
    observe)
      log "Release action is observe; skip config publish."
      printf ''
      ;;
    publish-current)
      log "Publishing current config from ${primary_url}"
      publish_current_config "$primary_url"
      ;;
    save-draft-and-publish)
      log "Saving draft and publishing config from ${primary_url}"
      local payload_file="$PISCES_DRILL_TMP/publish.json"
      local draft_output="$PISCES_DRILL_TMP/draft-save-response.json"
      [[ -n "${PISCES_DRAFT_PAYLOAD_FILE:-}" ]] || die "PISCES_DRAFT_PAYLOAD_FILE is required"
      [[ -f "$PISCES_DRAFT_PAYLOAD_FILE" ]] || die "Draft payload file not found: $PISCES_DRAFT_PAYLOAD_FILE"
      python3 -m json.tool "$PISCES_DRAFT_PAYLOAD_FILE" >/dev/null
      request_json PUT "${primary_url}/experiments/${PISCES_EXPERIMENT_ID}/config-draft" \
        "$PISCES_MANAGEMENT_API_KEY" "$PISCES_DRAFT_PAYLOAD_FILE" "$draft_output"
      write_publish_payload "$payload_file"
      request_json POST "${primary_url}/experiments/${PISCES_EXPERIMENT_ID}/config-draft/publish" \
        "$PISCES_MANAGEMENT_API_KEY" "$payload_file" "$PISCES_DRILL_TMP/draft-publish-response.json"
      json_value "$PISCES_DRILL_TMP/draft-publish-response.json" "data.configVersion"
      ;;
    *)
      die "Unsupported PISCES_RELEASE_ACTION: $PISCES_RELEASE_ACTION"
      ;;
  esac
}

poll_version_convergence() {
  local target_version="$1"
  local known_version="$2"
  [[ -n "$target_version" ]] || return 0

  log "Waiting for all instances to converge to configVersion=${target_version}"
  local deadline=$((SECONDS + PISCES_CONVERGENCE_TIMEOUT_SECONDS))
  local -a urls
  IFS=',' read -r -a urls <<< "$PISCES_INSTANCE_URLS_NORMALIZED"

  while (( SECONDS <= deadline )); do
    local all_ready=1
    for index in "${!urls[@]}"; do
      local output_file="$PISCES_DRILL_TMP/version-${index}.json"
      local current_version
      current_version="$(fetch_runtime_version_check "${urls[$index]}" "$known_version" "$PISCES_VERSION_WAIT_MILLIS" "$output_file")"
      log "Instance ${urls[$index]} currentVersion=${current_version}"
      if [[ "$current_version" != "$target_version" ]]; then
        all_ready=0
      fi
    done
    if [[ "$all_ready" -eq 1 ]]; then
      log "All instances converged to configVersion=${target_version}"
      return 0
    fi
    sleep 2
  done

  die "Timed out waiting for configVersion=${target_version}"
}

run_assignment_load() {
  if [[ "$PISCES_ASSIGNMENT_REQUESTS" -le 0 ]]; then
    log "Assignment load skipped because PISCES_ASSIGNMENT_REQUESTS=${PISCES_ASSIGNMENT_REQUESTS}"
    return 0
  fi

  log "Sending ${PISCES_ASSIGNMENT_REQUESTS} assign/trace requests with concurrency=${PISCES_ASSIGNMENT_CONCURRENCY}"
  export PISCES_INSTANCE_URLS_NORMALIZED
  export PISCES_EXPERIMENT_ID
  export PISCES_RUNTIME_API_KEY
  export PISCES_VISITOR_PREFIX
  export PISCES_DRILL_TMP

  local worker_script="$PISCES_DRILL_TMP/assignment-worker.sh"
  cat > "$worker_script" <<'WORKER'
#!/usr/bin/env bash
set -euo pipefail

index="$1"
IFS="," read -r -a urls <<< "$PISCES_INSTANCE_URLS_NORMALIZED"
url_index=$(( (index - 1) % ${#urls[@]} ))
base_url="${urls[$url_index]}"
body_file="${PISCES_DRILL_TMP}/assignment-${index}.json"
metric_file="${PISCES_DRILL_TMP}/assignment-${index}.tsv"
payload="$(python3 - "$index" <<'PY'
import json
import os
import sys

index = int(sys.argv[1])
print(json.dumps({
    "experimentId": os.environ["PISCES_EXPERIMENT_ID"],
    "visitorId": f"{os.environ['PISCES_VISITOR_PREFIX']}_{index}",
    "attributes": {
        "drill": "runtime-plane-release",
        "index": index,
    },
}, ensure_ascii=False))
PY
)"
curl_result="$(curl -sS -o "$body_file" -w "%{http_code}\t%{time_total}" \
  -X POST "${base_url}/traffic/assign/trace" \
  -H "X-Pisces-Api-Key: ${PISCES_RUNTIME_API_KEY}" \
  -H "Content-Type: application/json" \
  --data "$payload" || printf "000\t0")"
python3 - "$index" "$base_url" "$curl_result" "$body_file" > "$metric_file" <<'PY'
import json
import sys

index, base_url, curl_result, body_file = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
parts = curl_result.split("\t")
http_status = parts[0] if parts else "000"
duration = parts[1] if len(parts) > 1 else "0"
code = ""
assigned = ""
source = ""
reason = ""
version = ""
error = ""
try:
    with open(body_file, encoding="utf-8") as source_file:
        payload = json.load(source_file)
    code = payload.get("code", "")
    data = payload.get("data") or {}
    assigned = data.get("assigned", "")
    source = data.get("source", "")
    reason = data.get("reason", "")
    version = data.get("configVersion", "")
    if not (http_status.isdigit() and 200 <= int(http_status) < 300 and code == 200):
        error = payload.get("message") or "request_failed"
except Exception as exc:
    error = str(exc)
print("\t".join(str(value) for value in [
    index, base_url, http_status, duration, code, assigned, source, reason, version, error
]))
PY
WORKER
  chmod +x "$worker_script"
  seq 1 "$PISCES_ASSIGNMENT_REQUESTS" | xargs -P "$PISCES_ASSIGNMENT_CONCURRENCY" -n 1 "$worker_script"

  python3 - "$PISCES_DRILL_TMP"/assignment-*.tsv <<'PY'
from collections import Counter
import glob
import sys

rows = []
for pattern in sys.argv[1:]:
    for file_name in glob.glob(pattern):
        with open(file_name, encoding="utf-8") as source:
            parts = source.read().rstrip("\n").split("\t")
            if len(parts) >= 10:
                rows.append(parts)

if not rows:
    print("No assignment result rows were produced.", file=sys.stderr)
    sys.exit(1)

total = len(rows)
ok_rows = [row for row in rows if row[2].isdigit() and 200 <= int(row[2]) < 300 and row[4] == "200"]
failed_rows = [row for row in rows if row not in ok_rows]
durations = sorted(float(row[3]) * 1000 for row in ok_rows)

def percentile(values, ratio):
    if not values:
        return 0.0
    index = min(len(values) - 1, max(0, round((len(values) - 1) * ratio)))
    return values[index]

print("Assignment load summary")
print(f"  total={total} ok={len(ok_rows)} failed={len(failed_rows)} assigned={sum(1 for row in ok_rows if row[5] == 'True')}")
print(f"  latency_ms p50={percentile(durations, 0.50):.2f} p95={percentile(durations, 0.95):.2f} p99={percentile(durations, 0.99):.2f}")
print(f"  by_instance={dict(Counter(row[1] for row in rows))}")
print(f"  versions={dict(Counter(row[8] or 'UNKNOWN' for row in ok_rows))}")
print(f"  sources={dict(Counter(row[6] or 'UNKNOWN' for row in ok_rows))}")
print(f"  reasons={dict(Counter(row[7] or 'UNKNOWN' for row in ok_rows))}")
if failed_rows:
    print("  failures:")
    for row in failed_rows[:10]:
        print(f"    index={row[0]} instance={row[1]} http={row[2]} code={row[4]} error={row[9]}")
    sys.exit(1)
PY
}

collect_prometheus_snapshot() {
  local -a urls
  IFS=',' read -r -a urls <<< "$PISCES_INSTANCE_URLS_NORMALIZED"
  log "Collecting runtime plane Prometheus metric samples"
  for base_url in "${urls[@]}"; do
    log "Metrics from ${base_url}"
    local metrics_file="$PISCES_DRILL_TMP/prometheus-${base_url//[^[:alnum:]]/-}.txt"
    if curl -fsS "${base_url}/actuator/prometheus" -o "$metrics_file" \
      && awk '/^(pisces_traffic_|pisces_config_change_broadcast_)/ {
        print
        count += 1
        if (count == 30) {
          exit
        }
      }
      END {
        if (count == 0) {
          exit 1
        }
      }' "$metrics_file"; then
      continue
    else
      log "No runtime plane metrics returned from ${base_url}"
    fi
  done
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  require_command curl
  require_command python3
  require_command xargs

  PISCES_INSTANCE_URLS_NORMALIZED="$(normalize_urls "${PISCES_INSTANCE_URLS:-http://localhost:9990/api}")"
  PISCES_EXPERIMENT_ID="${PISCES_EXPERIMENT_ID:-}"
  PISCES_RUNTIME_API_KEY="${PISCES_RUNTIME_API_KEY:-runtime-key}"
  PISCES_MANAGEMENT_API_KEY="${PISCES_MANAGEMENT_API_KEY:-ops-key}"
  PISCES_RELEASE_ACTION="${PISCES_RELEASE_ACTION:-observe}"
  PISCES_ASSIGNMENT_REQUESTS="${PISCES_ASSIGNMENT_REQUESTS:-200}"
  PISCES_ASSIGNMENT_CONCURRENCY="${PISCES_ASSIGNMENT_CONCURRENCY:-8}"
  PISCES_VISITOR_PREFIX="${PISCES_VISITOR_PREFIX:-drill-$(date +%s)}"
  PISCES_VERSION_WAIT_MILLIS="${PISCES_VERSION_WAIT_MILLIS:-25000}"
  PISCES_CONVERGENCE_TIMEOUT_SECONDS="${PISCES_CONVERGENCE_TIMEOUT_SECONDS:-60}"
  PISCES_PUBLISH_OPERATOR="${PISCES_PUBLISH_OPERATOR:-runtime-drill}"
  PISCES_PUBLISH_COMMENT="${PISCES_PUBLISH_COMMENT:-runtime plane release drill}"

  [[ -n "$PISCES_EXPERIMENT_ID" ]] || die "PISCES_EXPERIMENT_ID is required"
  [[ "$PISCES_ASSIGNMENT_REQUESTS" =~ ^[0-9]+$ ]] || die "PISCES_ASSIGNMENT_REQUESTS must be a number"
  [[ "$PISCES_ASSIGNMENT_CONCURRENCY" =~ ^[0-9]+$ ]] || die "PISCES_ASSIGNMENT_CONCURRENCY must be a number"
  [[ "$PISCES_ASSIGNMENT_CONCURRENCY" -gt 0 ]] || die "PISCES_ASSIGNMENT_CONCURRENCY must be greater than 0"
  [[ "$PISCES_VERSION_WAIT_MILLIS" =~ ^[0-9]+$ ]] || die "PISCES_VERSION_WAIT_MILLIS must be a number"
  [[ "$PISCES_CONVERGENCE_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] || die "PISCES_CONVERGENCE_TIMEOUT_SECONDS must be a number"
  case "$PISCES_RELEASE_ACTION" in
    observe|publish-current|save-draft-and-publish)
      ;;
    *)
      die "Unsupported PISCES_RELEASE_ACTION: $PISCES_RELEASE_ACTION"
      ;;
  esac

  export PISCES_INSTANCE_URLS_NORMALIZED
  export PISCES_EXPERIMENT_ID
  export PISCES_RUNTIME_API_KEY
  export PISCES_MANAGEMENT_API_KEY
  export PISCES_RELEASE_ACTION
  export PISCES_ASSIGNMENT_REQUESTS
  export PISCES_ASSIGNMENT_CONCURRENCY
  export PISCES_VISITOR_PREFIX
  export PISCES_VERSION_WAIT_MILLIS
  export PISCES_CONVERGENCE_TIMEOUT_SECONDS
  export PISCES_PUBLISH_OPERATOR
  export PISCES_PUBLISH_COMMENT

  PISCES_DRILL_TMP="$(mktemp -d "${TMPDIR:-/tmp}/pisces-runtime-drill.XXXXXX")"
  export PISCES_DRILL_TMP
  trap 'rm -rf "$PISCES_DRILL_TMP"' EXIT

  local -a urls
  IFS=',' read -r -a urls <<< "$PISCES_INSTANCE_URLS_NORMALIZED"
  local primary_url="${urls[0]}"

  log "Runtime drill instances: ${PISCES_INSTANCE_URLS_NORMALIZED}"
  log "Experiment: ${PISCES_EXPERIMENT_ID}"

  local baseline_version=""
  for index in "${!urls[@]}"; do
    local output_file="$PISCES_DRILL_TMP/config-${index}.json"
    local version
    version="$(fetch_runtime_config_version "${urls[$index]}" "$output_file")"
    log "Initial configVersion ${urls[$index]}=${version}"
    if [[ -z "$baseline_version" ]]; then
      baseline_version="$version"
    fi
  done

  local target_version
  target_version="$(publish_config_if_requested "$primary_url")"
  if [[ -n "$target_version" ]]; then
    log "Published target configVersion=${target_version}"
    poll_version_convergence "$target_version" "$baseline_version"
  fi

  run_assignment_load
  collect_prometheus_snapshot
  log "Runtime plane release drill completed."
}

main "$@"
