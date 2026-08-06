#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/production-infrastructure-local-dependency-check.sh

Environment:
  PISCES_REPO_ROOT                         Repository root. Default: inferred from this script.
  PISCES_LOCAL_ENV_FILE                    Local env file. Default: config/pisces-local.env.
  PISCES_LOCAL_STACK_ENV_FILE              Local dependency stack env file. Default: config/pisces-local-stack.env.
  PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE      JSON output. Default: target/pisces-production-infrastructure-local-dependency-check/summary.json.
  PISCES_LOCAL_DEPENDENCY_STRICT           Exit non-zero unless mandatory dependencies are ready. Default: false.
  PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL      Check MySQL connectivity and schema. Default: true.
  PISCES_LOCAL_DEPENDENCY_CHECK_REDIS      Check Redis connectivity. Default: true.
  PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER  Check Zookeeper port as a parity warning. Default: true.
  MYSQL_URL                                JDBC URL. Default: jdbc:mysql://localhost:3306/pisces?...
  MYSQL_USERNAME                           MySQL user. Default: root.
  MYSQL_PASSWORD                           MySQL password. Default: empty.
  SPRING_DATA_REDIS_HOST                   Redis host override. Default: localhost.
  SPRING_DATA_REDIS_PORT                   Redis port override. Default: 6379.
  PISCES_ZOOKEEPER_CONNECT_STRING          Zookeeper connect string. Default: localhost:2181.
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

command_status() {
  if command -v "$1" >/dev/null 2>&1; then
    printf 'present'
    return
  fi
  printf 'missing'
}

port_status() {
  local host="$1"
  local port="$2"
  if command -v nc >/dev/null 2>&1 && nc -z "$host" "$port" >/dev/null 2>&1; then
    printf 'open'
    return
  fi
  printf 'closed'
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
host = match.group("host") or "localhost"
port = match.group("port") or "3306"
database = match.group("database") or "pisces"
print(host, port, database)
PY
}

first_zookeeper_address() {
  python3 - "$1" <<'PY'
import sys

connect = sys.argv[1].split(",", 1)[0].strip()
if not connect:
    print("localhost 2181")
    raise SystemExit(0)
if ":" in connect:
    host, port = connect.rsplit(":", 1)
else:
    host, port = connect, "2181"
print(host or "localhost", port or "2181")
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

required_tables_csv() {
  local sql_dir="$PISCES_REPO_ROOT/pisces-service/src/main/resources/sql/mysql"
  if [[ ! -d "$sql_dir" ]]; then
    return
  fi
  python3 - "$sql_dir" <<'PY'
import re
import sys
from pathlib import Path

sql_dir = Path(sys.argv[1])
tables = set()
for path in sorted(sql_dir.glob("*.sql")):
    name = path.name
    if name.endswith("_migration.sql") or name.endswith("_index_migration.sql"):
        continue
    text = path.read_text(encoding="utf-8")
    tables.update(
        match.group(1)
        for match in re.finditer(
            r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+`?([A-Za-z0-9_]+)`?",
            text,
            flags=re.IGNORECASE,
        )
    )
print(",".join(sorted(tables)))
PY
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
  PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE="${PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE:-target/pisces-production-infrastructure-local-dependency-check/summary.json}"
  PISCES_LOCAL_DEPENDENCY_STRICT="${PISCES_LOCAL_DEPENDENCY_STRICT:-false}"
  PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL="${PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL:-true}"
  PISCES_LOCAL_DEPENDENCY_CHECK_REDIS="${PISCES_LOCAL_DEPENDENCY_CHECK_REDIS:-true}"
  PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER="${PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER:-true}"

  local env_file stack_env_file output_file
  env_file="$(resolve_path "$PISCES_LOCAL_ENV_FILE")"
  stack_env_file="$(resolve_path "$PISCES_LOCAL_STACK_ENV_FILE")"
  output_file="$(resolve_path "$PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE")"
  mkdir -p "$(dirname "$output_file")"

  local env_mysql_url env_mysql_username env_mysql_password env_redis_host env_redis_port env_zookeeper_connect
  env_mysql_url="${MYSQL_URL-}"
  env_mysql_username="${MYSQL_USERNAME-}"
  env_mysql_password="${MYSQL_PASSWORD-}"
  env_redis_host="${SPRING_DATA_REDIS_HOST-}"
  env_redis_port="${SPRING_DATA_REDIS_PORT-}"
  env_zookeeper_connect="${PISCES_ZOOKEEPER_CONNECT_STRING-}"

  if [[ -f "$stack_env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$stack_env_file"
    set +a
  fi
  if [[ -f "$env_file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$env_file"
    set +a
  fi

  [[ -n "$env_mysql_url" ]] && MYSQL_URL="$env_mysql_url"
  [[ -n "$env_mysql_username" ]] && MYSQL_USERNAME="$env_mysql_username"
  [[ -n "$env_mysql_password" ]] && MYSQL_PASSWORD="$env_mysql_password"
  [[ -n "$env_redis_host" ]] && SPRING_DATA_REDIS_HOST="$env_redis_host"
  [[ -n "$env_redis_port" ]] && SPRING_DATA_REDIS_PORT="$env_redis_port"
  [[ -n "$env_zookeeper_connect" ]] && PISCES_ZOOKEEPER_CONNECT_STRING="$env_zookeeper_connect"

  MYSQL_URL="${MYSQL_URL:-jdbc:mysql://localhost:3306/pisces?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}"
  MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
  MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
  SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-localhost}"
  SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6379}"
  PISCES_ZOOKEEPER_CONNECT_STRING="${PISCES_ZOOKEEPER_CONNECT_STRING:-localhost:2181}"

  read -r PISCES_LOCAL_MYSQL_HOST PISCES_LOCAL_MYSQL_PORT PISCES_LOCAL_MYSQL_DATABASE < <(parse_mysql_url "$MYSQL_URL")
  read -r PISCES_LOCAL_ZOOKEEPER_HOST PISCES_LOCAL_ZOOKEEPER_PORT < <(first_zookeeper_address "$PISCES_ZOOKEEPER_CONNECT_STRING")

  local mysql_port_status mysql_connection_status mysql_connection_message mysql_database_status
  local mysql_present_tables mysql_missing_tables mysql_table_status mysql_required_tables
  local redis_port_status redis_ping_status redis_ping_message zookeeper_port_status
  local app_port_status

  mysql_port_status="skipped"
  mysql_connection_status="skipped"
  mysql_connection_message=""
  mysql_database_status="skipped"
  mysql_present_tables=""
  mysql_missing_tables=""
  mysql_table_status="skipped"
  mysql_required_tables="$(required_tables_csv || true)"
  redis_port_status="skipped"
  redis_ping_status="skipped"
  redis_ping_message=""
  zookeeper_port_status="skipped"
  app_port_status="$(port_status localhost 9990)"

  if is_true "$PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL"; then
    mysql_port_status="$(port_status "$PISCES_LOCAL_MYSQL_HOST" "$PISCES_LOCAL_MYSQL_PORT")"
    if command -v mysql >/dev/null 2>&1; then
      set +e
      mysql_connection_message="$(run_mysql -e "SELECT 1;" 2>&1 >/dev/null)"
      local mysql_status=$?
      set -e
      if [[ "$mysql_status" -eq 0 ]]; then
        mysql_connection_status="ok"
        set +e
        local database_result
        database_result="$(run_mysql -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='${PISCES_LOCAL_MYSQL_DATABASE}';" 2>/dev/null)"
        local database_status=$?
        set -e
        if [[ "$database_status" -eq 0 && "$database_result" == "$PISCES_LOCAL_MYSQL_DATABASE" ]]; then
          mysql_database_status="present"
          set +e
          mysql_present_tables="$(run_mysql "$PISCES_LOCAL_MYSQL_DATABASE" -e "SHOW TABLES;" 2>/dev/null | paste -sd, -)"
          local table_query_status=$?
          set -e
          if [[ "$table_query_status" -eq 0 ]]; then
            mysql_table_status="checked"
          else
            mysql_table_status="query_failed"
          fi
        else
          mysql_database_status="missing"
        fi
      else
        mysql_connection_status="failed"
      fi
    else
      mysql_connection_status="mysql_cli_missing"
    fi
  fi

  if is_true "$PISCES_LOCAL_DEPENDENCY_CHECK_REDIS"; then
    redis_port_status="$(port_status "$SPRING_DATA_REDIS_HOST" "$SPRING_DATA_REDIS_PORT")"
    if command -v redis-cli >/dev/null 2>&1; then
      set +e
      redis_ping_message="$(redis-cli -h "$SPRING_DATA_REDIS_HOST" -p "$SPRING_DATA_REDIS_PORT" ping 2>&1)"
      local redis_status=$?
      set -e
      if [[ "$redis_status" -eq 0 && "$redis_ping_message" == "PONG" ]]; then
        redis_ping_status="ok"
      else
        redis_ping_status="failed"
      fi
    else
      redis_ping_status="redis_cli_missing"
    fi
  fi

  if is_true "$PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER"; then
    zookeeper_port_status="$(port_status "$PISCES_LOCAL_ZOOKEEPER_HOST" "$PISCES_LOCAL_ZOOKEEPER_PORT")"
  fi

  export PISCES_REPO_ROOT
  export PISCES_LOCAL_ENV_FILE_RESOLVED="$env_file"
  export PISCES_LOCAL_STACK_ENV_FILE_RESOLVED="$stack_env_file"
  export PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE_RESOLVED="$output_file"
  export PISCES_LOCAL_DEPENDENCY_STRICT
  export PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL
  export PISCES_LOCAL_DEPENDENCY_CHECK_REDIS
  export PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER
  export PISCES_LOCAL_JAVA_STATUS="$(command_status java)"
  export PISCES_LOCAL_MAVEN_STATUS="$(command_status mvn)"
  export PISCES_LOCAL_MYSQL_CLI_STATUS="$(command_status mysql)"
  export PISCES_LOCAL_REDIS_CLI_STATUS="$(command_status redis-cli)"
  export PISCES_LOCAL_NC_STATUS="$(command_status nc)"
  export PISCES_LOCAL_MYSQL_HOST
  export PISCES_LOCAL_MYSQL_PORT
  export PISCES_LOCAL_MYSQL_DATABASE
  export MYSQL_PASSWORD
  export PISCES_LOCAL_MYSQL_PORT_STATUS="$mysql_port_status"
  export PISCES_LOCAL_MYSQL_CONNECTION_STATUS="$mysql_connection_status"
  export PISCES_LOCAL_MYSQL_CONNECTION_MESSAGE="$mysql_connection_message"
  export PISCES_LOCAL_MYSQL_DATABASE_STATUS="$mysql_database_status"
  export PISCES_LOCAL_MYSQL_TABLE_STATUS="$mysql_table_status"
  export PISCES_LOCAL_MYSQL_REQUIRED_TABLES="$mysql_required_tables"
  export PISCES_LOCAL_MYSQL_PRESENT_TABLES="$mysql_present_tables"
  export SPRING_DATA_REDIS_HOST
  export SPRING_DATA_REDIS_PORT
  export PISCES_LOCAL_REDIS_PORT_STATUS="$redis_port_status"
  export PISCES_LOCAL_REDIS_PING_STATUS="$redis_ping_status"
  export PISCES_LOCAL_REDIS_PING_MESSAGE="$redis_ping_message"
  export PISCES_LOCAL_ZOOKEEPER_CONNECT_STRING="$PISCES_ZOOKEEPER_CONNECT_STRING"
  export PISCES_LOCAL_ZOOKEEPER_HOST
  export PISCES_LOCAL_ZOOKEEPER_PORT
  export PISCES_LOCAL_ZOOKEEPER_PORT_STATUS="$zookeeper_port_status"
  export PISCES_LOCAL_APP_PORT_STATUS="$app_port_status"
  if [[ -f "$stack_env_file" ]]; then
    export PISCES_LOCAL_STACK_ENV_FILE_EXISTS=true
  else
    export PISCES_LOCAL_STACK_ENV_FILE_EXISTS=false
  fi

  set +e
  python3 <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

repo_root = Path(os.environ["PISCES_REPO_ROOT"])
output_file = Path(os.environ["PISCES_LOCAL_DEPENDENCY_OUTPUT_FILE_RESOLVED"])
env_file = Path(os.environ["PISCES_LOCAL_ENV_FILE_RESOLVED"])
stack_env_file = Path(os.environ["PISCES_LOCAL_STACK_ENV_FILE_RESOLVED"])
strict = os.environ["PISCES_LOCAL_DEPENDENCY_STRICT"].lower() in {"true", "1", "yes", "y"}

checks = []


def display(path):
    try:
        return str(path.relative_to(repo_root))
    except ValueError:
        return str(path)


def add_check(name, status, actual, expected, mandatory=True, action=None):
    item = {
        "name": name,
        "status": status,
        "actual": actual,
        "expected": expected,
        "mandatory": mandatory,
    }
    if action:
        item["action"] = action
    checks.append(item)


def enabled(name):
    return os.environ[name].lower() in {"true", "1", "yes", "y"}


def csv(value):
    return [item.strip() for item in value.split(",") if item.strip()]


def sanitize(message):
    sanitized = message or ""
    password = os.environ.get("MYSQL_PASSWORD") or ""
    if password:
        sanitized = sanitized.replace(password, "***")
    return sanitized[:260]


java_status = os.environ["PISCES_LOCAL_JAVA_STATUS"]
maven_status = os.environ["PISCES_LOCAL_MAVEN_STATUS"]
mysql_cli_status = os.environ["PISCES_LOCAL_MYSQL_CLI_STATUS"]
redis_cli_status = os.environ["PISCES_LOCAL_REDIS_CLI_STATUS"]
nc_status = os.environ["PISCES_LOCAL_NC_STATUS"]
mysql_host = os.environ["PISCES_LOCAL_MYSQL_HOST"]
mysql_port = os.environ["PISCES_LOCAL_MYSQL_PORT"]
mysql_database = os.environ["PISCES_LOCAL_MYSQL_DATABASE"]
mysql_required_tables = csv(os.environ["PISCES_LOCAL_MYSQL_REQUIRED_TABLES"])
mysql_present_tables = set(csv(os.environ["PISCES_LOCAL_MYSQL_PRESENT_TABLES"]))
mysql_missing_tables = sorted(set(mysql_required_tables) - mysql_present_tables)
redis_host = os.environ["SPRING_DATA_REDIS_HOST"]
redis_port = os.environ["SPRING_DATA_REDIS_PORT"]
zk_connect = os.environ["PISCES_LOCAL_ZOOKEEPER_CONNECT_STRING"]
zk_host = os.environ["PISCES_LOCAL_ZOOKEEPER_HOST"]
zk_port = os.environ["PISCES_LOCAL_ZOOKEEPER_PORT"]

add_check(
    "java available",
    "PASS" if java_status == "present" else "FAIL",
    java_status,
    "present",
    True,
    "Install Java 21 before starting the backend.",
)
add_check(
    "maven available",
    "PASS" if maven_status == "present" else "FAIL",
    maven_status,
    "present",
    True,
    "Install Maven before running backend and release package checks.",
)
add_check(
    "local env file present",
    "PASS" if env_file.is_file() else "HOLD",
    display(env_file) if env_file.is_file() else "missing",
    display(env_file),
    True,
    "Run scripts/production-infrastructure-local-bootstrap.sh.",
)
add_check(
    "network port checker available",
    "PASS" if nc_status == "present" else "FAIL",
    nc_status,
    "present",
    True,
    "Install nc/netcat so local dependency probes can run.",
)

if enabled("PISCES_LOCAL_DEPENDENCY_CHECK_MYSQL"):
    mysql_port_status = os.environ["PISCES_LOCAL_MYSQL_PORT_STATUS"]
    mysql_connection_status = os.environ["PISCES_LOCAL_MYSQL_CONNECTION_STATUS"]
    mysql_database_status = os.environ["PISCES_LOCAL_MYSQL_DATABASE_STATUS"]
    mysql_table_status = os.environ.get("PISCES_LOCAL_MYSQL_TABLE_STATUS", "")
    add_check(
        "mysql client available",
        "PASS" if mysql_cli_status == "present" else "FAIL",
        mysql_cli_status,
        "present",
        True,
        "Install mysql client or put it on PATH.",
    )
    add_check(
        "mysql port reachable",
        "PASS" if mysql_port_status == "open" else "HOLD",
        f"{mysql_host}:{mysql_port} {mysql_port_status}",
        "open",
        True,
        "Start MySQL locally, or update MYSQL_URL in config/pisces-local.env if it uses a different host or port.",
    )
    add_check(
        "mysql credentials valid",
        "PASS" if mysql_connection_status == "ok" else "HOLD",
        mysql_connection_status if mysql_connection_status == "ok" else sanitize(os.environ["PISCES_LOCAL_MYSQL_CONNECTION_MESSAGE"]),
        "SELECT 1 succeeds",
        True,
        "If your local MySQL is not root with an empty password, set MYSQL_USERNAME and MYSQL_PASSWORD in config/pisces-local.env.",
    )
    add_check(
        "mysql database exists",
        "PASS" if mysql_database_status == "present" else "HOLD",
        mysql_database_status,
        mysql_database,
        True,
        f"Create database {mysql_database} and apply SQL files under pisces-service/src/main/resources/sql/mysql.",
    )
    add_check(
        "mysql schema tables present",
        "PASS" if not mysql_missing_tables and mysql_database_status == "present" else "HOLD",
        f"missing={mysql_missing_tables[:12]} count={len(mysql_missing_tables)}",
        f"{len(mysql_required_tables)} required tables",
        True,
        "Apply base SQL files and migration SQL files before starting local evidence collection.",
    )
else:
    add_check("mysql dependency check", "PASS", "skipped", "skipped", True)

if enabled("PISCES_LOCAL_DEPENDENCY_CHECK_REDIS"):
    redis_port_status = os.environ["PISCES_LOCAL_REDIS_PORT_STATUS"]
    redis_ping_status = os.environ["PISCES_LOCAL_REDIS_PING_STATUS"]
    add_check(
        "redis client available",
        "PASS" if redis_cli_status == "present" else "FAIL",
        redis_cli_status,
        "present",
        True,
        "Install redis-cli or put it on PATH.",
    )
    add_check(
        "redis port reachable",
        "PASS" if redis_port_status == "open" else "HOLD",
        f"{redis_host}:{redis_port} {redis_port_status}",
        "open",
        True,
        "Start Redis locally before running runtime drill and Redis fault evidence.",
    )
    add_check(
        "redis ping succeeds",
        "PASS" if redis_ping_status == "ok" else "HOLD",
        "PONG" if redis_ping_status == "ok" else sanitize(os.environ["PISCES_LOCAL_REDIS_PING_MESSAGE"]),
        "PONG",
        True,
        "Start Redis locally and rerun this dependency check.",
    )
else:
    add_check("redis dependency check", "PASS", "skipped", "skipped", True)

if enabled("PISCES_LOCAL_DEPENDENCY_CHECK_ZOOKEEPER"):
    zk_port_status = os.environ["PISCES_LOCAL_ZOOKEEPER_PORT_STATUS"]
    add_check(
        "zookeeper port reachable",
        "PASS" if zk_port_status == "open" else "WARN",
        f"{zk_host}:{zk_port} {zk_port_status}",
        "open for full local parity",
        False,
        "Zookeeper is optional for core experiment config because the service falls back to DB storage; start it for full layer-config parity.",
    )
else:
    add_check("zookeeper dependency check", "PASS", "skipped", "skipped", False)

app_port_status = os.environ["PISCES_LOCAL_APP_PORT_STATUS"]
add_check(
    "backend port before start",
    "PASS" if app_port_status in {"closed", "open"} else "WARN",
    f"localhost:9990 {app_port_status}",
    "closed before start or open when backend is already running",
    False,
)

failed = [check for check in checks if check["mandatory"] and check["status"] == "FAIL"]
holds = [check for check in checks if check["mandatory"] and check["status"] == "HOLD"]
warnings = [check for check in checks if check["status"] == "WARN"]
if failed:
    status = "BLOCKED"
elif holds:
    status = "NEEDS_LOCAL_DEPENDENCIES"
else:
    status = "READY_FOR_LOCAL_SERVICE_START"

next_commands = []
if status != "READY_FOR_LOCAL_SERVICE_START":
    next_commands.extend([
        "bash scripts/production-infrastructure-local-dependency-stack.sh up",
        "fix mandatory HOLD/FAIL checks in this summary",
        "bash scripts/production-infrastructure-local-mysql-schema-apply.sh",
        "bash scripts/production-infrastructure-local-dependency-check.sh",
    ])
next_commands.extend([
    "source config/pisces-local-stack.env 2>/dev/null || true",
    "source config/pisces-local.env",
    "bash scripts/production-infrastructure-local-service.sh start",
    "bash scripts/production-infrastructure-local-readiness.sh",
])

summary = {
    "summaryType": "pisces-production-infrastructure-local-dependency-check",
    "summaryVersion": 1,
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "status": status,
    "targetEnvironment": "local",
    "envFile": display(env_file),
    "stackEnvFile": display(stack_env_file),
    "stackEnvFilePresent": os.environ["PISCES_LOCAL_STACK_ENV_FILE_EXISTS"] == "true",
    "mysql": {
        "host": mysql_host,
        "port": int(mysql_port),
        "database": mysql_database,
        "requiredTableCount": len(mysql_required_tables),
        "missingTables": mysql_missing_tables,
    },
    "redis": {
        "host": redis_host,
        "port": int(redis_port),
    },
    "zookeeper": {
        "connectString": zk_connect,
        "host": zk_host,
        "port": int(zk_port),
    },
    "checks": checks,
    "warningCount": len(warnings),
    "nextCommands": next_commands,
}

output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Production infrastructure local dependency check written: {output_file} status={status}", file=sys.stderr)
if strict and status != "READY_FOR_LOCAL_SERVICE_START":
    sys.exit(1)
PY
  local python_status=$?
  set -e
  return "$python_status"
}

main "$@"
