#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  PISCES_EXPERIMENT_ID=exp_price_001 scripts/runtime-plane-capacity-baseline.sh

Environment:
  PISCES_INSTANCE_URLS              Comma separated service base URLs. Default: http://localhost:9990/api
  PISCES_EXPERIMENT_ID              Required experiment ID.
  PISCES_RUNTIME_API_KEY            Runtime scope API key. Default: runtime-key
  PISCES_CAPACITY_STEPS             Comma separated requests:concurrency steps. Default: 100:8,500:16,1000:32
  PISCES_CAPACITY_MAX_ERROR_RATE    Max allowed error rate per step. Default: 0
  PISCES_CAPACITY_MAX_P95_MS        Max allowed p95 latency per step. Default: 500
  PISCES_CAPACITY_OUTPUT_FILE       Optional JSONL output path.
  PISCES_VISITOR_PREFIX             Visitor ID prefix. Default: capacity-<epoch>
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

validate_steps() {
  local raw_steps="$1"
  local -a steps
  IFS=',' read -r -a steps <<< "$raw_steps"
  [[ "${#steps[@]}" -gt 0 ]] || die "PISCES_CAPACITY_STEPS is empty"
  for raw_step in "${steps[@]}"; do
    local step requests concurrency
    step="$(trim "$raw_step")"
    [[ "$step" =~ ^[0-9]+:[0-9]+$ ]] || die "Invalid capacity step: $step"
    requests="${step%%:*}"
    concurrency="${step##*:}"
    [[ "$requests" -gt 0 ]] || die "Step requests must be greater than 0: $step"
    [[ "$concurrency" -gt 0 ]] || die "Step concurrency must be greater than 0: $step"
  done
}

run_step() {
  local step_name="$1"
  local request_count="$2"
  local concurrency="$3"
  local step_dir="$PISCES_BASELINE_TMP/$step_name"
  mkdir -p "$step_dir"

  log "Running capacity step ${step_name}: requests=${request_count}, concurrency=${concurrency}"
  export PISCES_INSTANCE_URLS_NORMALIZED
  export PISCES_EXPERIMENT_ID
  export PISCES_RUNTIME_API_KEY
  export PISCES_VISITOR_PREFIX
  export step_dir

  local worker_script="$step_dir/assignment-worker.sh"
  cat > "$worker_script" <<'WORKER'
#!/usr/bin/env bash
set -euo pipefail

index="$1"
IFS="," read -r -a urls <<< "$PISCES_INSTANCE_URLS_NORMALIZED"
url_index=$(( (index - 1) % ${#urls[@]} ))
base_url="${urls[$url_index]}"
body_file="${step_dir}/assignment-${index}.json"
metric_file="${step_dir}/assignment-${index}.tsv"
payload="$(python3 - "$index" <<'PY'
import json
import os
import sys

index = int(sys.argv[1])
print(json.dumps({
    "experimentId": os.environ["PISCES_EXPERIMENT_ID"],
    "visitorId": f"{os.environ['PISCES_VISITOR_PREFIX']}_{index}",
    "attributes": {
        "baseline": "runtime-plane-capacity",
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
  seq 1 "$request_count" | xargs -P "$concurrency" -n 1 "$worker_script"

  python3 - "$step_name" "$request_count" "$concurrency" "$PISCES_CAPACITY_MAX_ERROR_RATE" \
    "$PISCES_CAPACITY_MAX_P95_MS" "$step_dir"/assignment-*.tsv <<'PY'
from collections import Counter
import glob
import json
import sys

step_name = sys.argv[1]
request_count = int(sys.argv[2])
concurrency = int(sys.argv[3])
max_error_rate = float(sys.argv[4])
max_p95_ms = float(sys.argv[5])
rows = []
for pattern in sys.argv[6:]:
    for file_name in glob.glob(pattern):
        with open(file_name, encoding="utf-8") as source:
            parts = source.read().rstrip("\n").split("\t")
            if len(parts) >= 10:
                rows.append(parts)

if not rows:
    print(f"No rows for {step_name}", file=sys.stderr)
    sys.exit(2)

ok_rows = [row for row in rows if row[2].isdigit() and 200 <= int(row[2]) < 300 and row[4] == "200"]
failed_rows = [row for row in rows if row not in ok_rows]
durations = sorted(float(row[3]) * 1000 for row in ok_rows)

def percentile(values, ratio):
    if not values:
        return 0.0
    index = min(len(values) - 1, max(0, round((len(values) - 1) * ratio)))
    return values[index]

summary = {
    "step": step_name,
    "requests": request_count,
    "concurrency": concurrency,
    "total": len(rows),
    "ok": len(ok_rows),
    "failed": len(failed_rows),
    "errorRate": len(failed_rows) / len(rows),
    "latencyMs": {
        "p50": percentile(durations, 0.50),
        "p95": percentile(durations, 0.95),
        "p99": percentile(durations, 0.99),
    },
    "byInstance": dict(Counter(row[1] for row in rows)),
    "versions": dict(Counter(row[8] or "UNKNOWN" for row in ok_rows)),
    "sources": dict(Counter(row[6] or "UNKNOWN" for row in ok_rows)),
    "reasons": dict(Counter(row[7] or "UNKNOWN" for row in ok_rows)),
}
print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
print(
    f"Capacity step {step_name}: total={summary['total']} ok={summary['ok']} "
    f"failed={summary['failed']} errorRate={summary['errorRate']:.6f} "
    f"p50={summary['latencyMs']['p50']:.2f}ms p95={summary['latencyMs']['p95']:.2f}ms "
    f"p99={summary['latencyMs']['p99']:.2f}ms",
    file=sys.stderr,
)
if summary["errorRate"] > max_error_rate or summary["latencyMs"]["p95"] > max_p95_ms:
    print(
        f"Step {step_name} exceeded thresholds: maxErrorRate={max_error_rate}, maxP95Ms={max_p95_ms}",
        file=sys.stderr,
    )
    sys.exit(1)
PY
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
  PISCES_CAPACITY_STEPS="${PISCES_CAPACITY_STEPS:-100:8,500:16,1000:32}"
  PISCES_CAPACITY_MAX_ERROR_RATE="${PISCES_CAPACITY_MAX_ERROR_RATE:-0}"
  PISCES_CAPACITY_MAX_P95_MS="${PISCES_CAPACITY_MAX_P95_MS:-500}"
  PISCES_VISITOR_PREFIX="${PISCES_VISITOR_PREFIX:-capacity-$(date +%s)}"

  [[ -n "$PISCES_EXPERIMENT_ID" ]] || die "PISCES_EXPERIMENT_ID is required"
  validate_steps "$PISCES_CAPACITY_STEPS"
  [[ "$PISCES_CAPACITY_MAX_ERROR_RATE" =~ ^[0-9]+([.][0-9]+)?$ ]] \
    || die "PISCES_CAPACITY_MAX_ERROR_RATE must be a number"
  [[ "$PISCES_CAPACITY_MAX_P95_MS" =~ ^[0-9]+([.][0-9]+)?$ ]] \
    || die "PISCES_CAPACITY_MAX_P95_MS must be a number"

  export PISCES_INSTANCE_URLS_NORMALIZED
  export PISCES_EXPERIMENT_ID
  export PISCES_RUNTIME_API_KEY
  export PISCES_VISITOR_PREFIX
  export PISCES_CAPACITY_MAX_ERROR_RATE
  export PISCES_CAPACITY_MAX_P95_MS

  PISCES_BASELINE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/pisces-runtime-capacity.XXXXXX")"
  export PISCES_BASELINE_TMP
  trap 'rm -rf "$PISCES_BASELINE_TMP"' EXIT

  local output_file="${PISCES_CAPACITY_OUTPUT_FILE:-target/pisces-runtime-capacity-baseline-$(date +%Y%m%d%H%M%S).jsonl}"
  local -a steps
  IFS=',' read -r -a steps <<< "$PISCES_CAPACITY_STEPS"

  log "Runtime capacity instances: ${PISCES_INSTANCE_URLS_NORMALIZED}"
  log "Experiment: ${PISCES_EXPERIMENT_ID}"
  log "Capacity steps: ${PISCES_CAPACITY_STEPS}"

  mkdir -p "$(dirname "$output_file")"
  : > "$output_file"
  for raw_step in "${steps[@]}"; do
    local step request_count concurrency step_name summary
    step="$(trim "$raw_step")"
    request_count="${step%%:*}"
    concurrency="${step##*:}"
    step_name="r${request_count}_c${concurrency}"
    summary="$(run_step "$step_name" "$request_count" "$concurrency")"
    printf '%s\n' "$summary" >> "$output_file"
  done

  log "Capacity baseline written to ${output_file}"
}

main "$@"
