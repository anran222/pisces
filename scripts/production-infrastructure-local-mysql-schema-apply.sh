#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-mysql-schema-apply.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local dependency stack env file. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE    JSON output. Default: target/pisces-production-infrastructure-local-mysql-schema/summary.json.
  PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN        Only plan files, do not connect or apply. Default: false.
  PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL Allow non-local MYSQL_URL host. Default: false.
  MYSQL_URL                                JDBC URL. Default: jdbc:mysql://localhost:3306/pisces?...
  MYSQL_USERNAME                           MySQL user. Default: root.
  MYSQL_PASSWORD                           MySQL password. Default: empty.

This applies only base CREATE TABLE scripts. Migration files are skipped because
fresh base DDL already includes the current columns and indexes.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

die() {
  log "ERROR: $*"
  exit 1
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

resolve_path() {
  case "$1" in
    /*)
      printf '%s' "$1"
      ;;
    *)
      printf '%s/%s' "$PISCES_REPO_ROOT" "$1"
      ;;
  esac
}

parse_mysql_url() {
  python3 - "$1" <<'PY'
import re
import sys

url = sys.argv[1]
match = re.match(r"^jdbc:mysql://(?P<host>[^:/?]+)(?::(?P<port>\d+))?/(?P<database>[^?]+)", url)
if not match:
    print("localhost 3306 pisces")
    raise SystemExit(0)
print(match.group("host") or "localhost", match.group("port") or "3306", match.group("database") or "pisces")
PY
}

sql_plan_json() {
  python3 - "$PISCES_REPO_ROOT/pisces-service/src/main/resources/sql/mysql" <<'PY'
import json
import sys
from pathlib import Path

sql_dir = Path(sys.argv[1])
base_files = []
skipped_files = []
for path in sorted(sql_dir.glob("*.sql")):
    name = path.name
    if name.endswith("_migration.sql") or name.endswith("_index_migration.sql"):
        skipped_files.append(str(path))
    else:
        base_files.append(str(path))
print(json.dumps({"baseFiles": base_files, "skippedMigrationFiles": skipped_files}, ensure_ascii=False))
PY
}

run_mysql() {
  local -a mysql_args=(
    --protocol=TCP
    -h "$PISCES_LOCAL_MYSQL_HOST"
    -P "$PISCES_LOCAL_MYSQL_PORT"
    -u "${MYSQL_USERNAME:-root}"
    --batch
    --skip-column-names
  )
  if [[ -n "${MYSQL_PASSWORD:-}" ]]; then
    MYSQL_PWD="$MYSQL_PASSWORD" mysql "${mysql_args[@]}" "$@"
    return
  fi
  mysql "${mysql_args[@]}" "$@"
}

main() {
  if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
  fi

  command -v python3 >/dev/null 2>&1 || die "Missing command: python3"

  PISCES_REPO_ROOT="$(resolve_repo_root)"
  PISCES_LOCAL_ENV_FILE="${PISCES_LOCAL_ENV_FILE:-config/pisces-local.env}"
  PISCES_LOCAL_STACK_ENV_FILE="${PISCES_LOCAL_STACK_ENV_FILE:-config/pisces-local-stack.env}"
  PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE="${PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE:-target/pisces-production-infrastructure-local-mysql-schema/summary.json}"
  PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN="${PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN:-false}"
  PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL="${PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL:-false}"

  local env_file stack_env_file output_file
  env_file="$(resolve_path "$PISCES_LOCAL_ENV_FILE")"
  stack_env_file="$(resolve_path "$PISCES_LOCAL_STACK_ENV_FILE")"
  output_file="$(resolve_path "$PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  local env_mysql_url env_mysql_username env_mysql_password
  env_mysql_url="${MYSQL_URL-}"
  env_mysql_username="${MYSQL_USERNAME-}"
  env_mysql_password="${MYSQL_PASSWORD-}"

  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi
  if [[ -f "$stack_env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$stack_env_file"
    set +a
  fi

  [[ -n "$env_mysql_url" ]] && MYSQL_URL="$env_mysql_url"
  [[ -n "$env_mysql_username" ]] && MYSQL_USERNAME="$env_mysql_username"
  [[ -n "$env_mysql_password" ]] && MYSQL_PASSWORD="$env_mysql_password"

  MYSQL_URL="${MYSQL_URL:-jdbc:mysql://localhost:3306/pisces?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}"
  MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
  MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
  read -r PISCES_LOCAL_MYSQL_HOST PISCES_LOCAL_MYSQL_PORT PISCES_LOCAL_MYSQL_DATABASE < <(parse_mysql_url "$MYSQL_URL")

  local plan_json
  plan_json="$(sql_plan_json)"

  local -a local_hosts=(localhost 127.0.0.1 ::1)
  local is_local_host=false
  local host
  for host in "${local_hosts[@]}"; do
    if [[ "$PISCES_LOCAL_MYSQL_HOST" == "$host" ]]; then
      is_local_host=true
    fi
  done

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_ENV_FILE_RESOLVED="$env_file"
  export PISCES_LOCAL_STACK_ENV_FILE_RESOLVED="$stack_env_file"
  export PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE_RESOLVED="$output_file"
  export PISCES_LOCAL_MYSQL_SCHEMA_PLAN_JSON="$plan_json"
  export PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN
  export PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL
  export PISCES_LOCAL_MYSQL_HOST
  export PISCES_LOCAL_MYSQL_PORT
  export PISCES_LOCAL_MYSQL_DATABASE
  export PISCES_LOCAL_MYSQL_IS_LOCAL_HOST="$is_local_host"
  export MYSQL_PASSWORD
  if [[ -f "$stack_env_file" ]]; then
    export PISCES_LOCAL_STACK_ENV_FILE_EXISTS=true
  else
    export PISCES_LOCAL_STACK_ENV_FILE_EXISTS=false
  fi

  if [[ "$is_local_host" != "true" ]] && ! is_true "$PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL"; then
    python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE_RESOLVED"])
plan = json.loads(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_PLAN_JSON"])
summary = {
    "summaryType": "pisces-production-infrastructure-local-mysql-schema-apply",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": "REFUSED_NONLOCAL_MYSQL",
    "envFile": os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"],
    "stackEnvFile": os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"],
    "stackEnvFilePresent": os.environ["PISCES_LOCAL_STACK_ENV_FILE_EXISTS"] == "true",
    "mysql": {
        "host": os.environ["PISCES_LOCAL_MYSQL_HOST"],
        "port": int(os.environ["PISCES_LOCAL_MYSQL_PORT"]),
        "database": os.environ["PISCES_LOCAL_MYSQL_DATABASE"],
    },
    "baseFiles": plan["baseFiles"],
    "skippedMigrationFiles": plan["skippedMigrationFiles"],
    "appliedFiles": [],
    "failedFile": None,
    "message": "Refusing to apply local schema to non-local MYSQL_URL without PISCES_LOCAL_MYSQL_SCHEMA_ALLOW_NONLOCAL=true.",
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local MySQL schema apply written: {output_file} status=REFUSED_NONLOCAL_MYSQL", file=os.sys.stderr)
PY
    return 2
  fi

  if is_true "$PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN"; then
    python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE_RESOLVED"])
plan = json.loads(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_PLAN_JSON"])
summary = {
    "summaryType": "pisces-production-infrastructure-local-mysql-schema-apply",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": "PLAN_ONLY",
    "envFile": os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"],
    "stackEnvFile": os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"],
    "stackEnvFilePresent": os.environ["PISCES_LOCAL_STACK_ENV_FILE_EXISTS"] == "true",
    "mysql": {
        "host": os.environ["PISCES_LOCAL_MYSQL_HOST"],
        "port": int(os.environ["PISCES_LOCAL_MYSQL_PORT"]),
        "database": os.environ["PISCES_LOCAL_MYSQL_DATABASE"],
    },
    "baseFiles": plan["baseFiles"],
    "skippedMigrationFiles": plan["skippedMigrationFiles"],
    "appliedFiles": [],
    "failedFile": None,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local MySQL schema apply written: {output_file} status=PLAN_ONLY", file=os.sys.stderr)
PY
    return 0
  fi

  command -v mysql >/dev/null 2>&1 || die "Missing command: mysql"

  local applied_files="" failed_file="" failure_message="" status="PASS"
  local error_file
  error_file="$(dirname "$output_file")/mysql-error.log"
  set +e
  run_mysql -e "CREATE DATABASE IF NOT EXISTS \`$PISCES_LOCAL_MYSQL_DATABASE\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >/dev/null 2>"$error_file"
  local create_status=$?
  set -e
  if [[ "$create_status" -ne 0 ]]; then
    status="FAILED"
    failure_message="$(cat "$error_file" 2>/dev/null || true)"
  else
    while IFS= read -r sql_file; do
      set +e
      run_mysql "$PISCES_LOCAL_MYSQL_DATABASE" < "$sql_file" >/dev/null 2>"$error_file"
      local apply_status=$?
      set -e
      if [[ "$apply_status" -ne 0 ]]; then
        status="FAILED"
        failed_file="$sql_file"
        failure_message="$(cat "$error_file" 2>/dev/null || true)"
        break
      fi
      if [[ -n "$applied_files" ]]; then
        applied_files="${applied_files},${sql_file}"
      else
        applied_files="$sql_file"
      fi
    done < <(python3 - <<'PY'
import json
import os
plan = json.loads(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_PLAN_JSON"])
for path in plan["baseFiles"]:
    print(path)
PY
)
  fi

  export PISCES_LOCAL_MYSQL_SCHEMA_STATUS="$status"
  export PISCES_LOCAL_MYSQL_SCHEMA_APPLIED_FILES="$applied_files"
  export PISCES_LOCAL_MYSQL_SCHEMA_FAILED_FILE="$failed_file"
  export PISCES_LOCAL_MYSQL_SCHEMA_FAILURE_MESSAGE="$failure_message"

  python3 <<'PY'
import json
import os
from datetime import datetime, timezone
from pathlib import Path

output_file = Path(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE_RESOLVED"])
plan = json.loads(os.environ["PISCES_LOCAL_MYSQL_SCHEMA_PLAN_JSON"])
password = os.environ.get("MYSQL_PASSWORD") or ""
failure_message = os.environ.get("PISCES_LOCAL_MYSQL_SCHEMA_FAILURE_MESSAGE", "")
if password:
    failure_message = failure_message.replace(password, "***")
applied_files = [
    item for item in os.environ.get("PISCES_LOCAL_MYSQL_SCHEMA_APPLIED_FILES", "").split(",")
    if item
]
summary = {
    "summaryType": "pisces-production-infrastructure-local-mysql-schema-apply",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": os.environ["PISCES_LOCAL_MYSQL_SCHEMA_STATUS"],
    "envFile": os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"],
    "stackEnvFile": os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"],
    "stackEnvFilePresent": os.environ["PISCES_LOCAL_STACK_ENV_FILE_EXISTS"] == "true",
    "mysql": {
        "host": os.environ["PISCES_LOCAL_MYSQL_HOST"],
        "port": int(os.environ["PISCES_LOCAL_MYSQL_PORT"]),
        "database": os.environ["PISCES_LOCAL_MYSQL_DATABASE"],
    },
    "baseFiles": plan["baseFiles"],
    "skippedMigrationFiles": plan["skippedMigrationFiles"],
    "appliedFiles": applied_files,
    "failedFile": os.environ.get("PISCES_LOCAL_MYSQL_SCHEMA_FAILED_FILE") or None,
    "failureMessage": failure_message[:500] if failure_message else None,
}
output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local MySQL schema apply written: {output_file} status={summary['status']}", file=os.sys.stderr)
PY

  [[ "$status" == "PASS" ]]
}

main "$@"
