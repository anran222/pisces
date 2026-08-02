#!/usr/bin/env bash

set -euo pipefail

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Missing command: %s\n' "$1" >&2
    exit 1
  }
}

require_command python3

smoke_root="${PISCES_LOCAL_MYSQL_SCHEMA_SMOKE_ROOT:-target/pisces-production-infrastructure-local-mysql-schema-smoke}"
run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
workspace="$smoke_root/mysql-schema-smoke-$run_id"
summary_file="$workspace/schema-plan-summary.json"

PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE="$summary_file" \
PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN=true \
bash scripts/production-infrastructure-local-mysql-schema-apply.sh >/dev/null

python3 - "$summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary_file = Path(sys.argv[1])
summary = json.loads(summary_file.read_text(encoding="utf-8"))

if summary.get("summaryType") != "pisces-production-infrastructure-local-mysql-schema-apply":
    raise SystemExit("mysql schema summary type mismatch")
if summary.get("status") != "PLAN_ONLY":
    raise SystemExit(f"expected plan-only status: {summary.get('status')}")
base_files = summary.get("baseFiles") or []
skipped = summary.get("skippedMigrationFiles") or []
if not any(path.endswith("pisces_experiment_config.sql") for path in base_files):
    raise SystemExit("base plan should include experiment config table")
if not any(path.endswith("_migration.sql") for path in skipped):
    raise SystemExit("plan should skip migration files")
if any(path.endswith("_migration.sql") for path in base_files):
    raise SystemExit("base plan must not include migration files")
PY

nonlocal_summary_file="$workspace/schema-nonlocal-summary.json"
set +e
MYSQL_URL="jdbc:mysql://example.com:3306/pisces" \
PISCES_LOCAL_MYSQL_SCHEMA_OUTPUT_FILE="$nonlocal_summary_file" \
PISCES_LOCAL_MYSQL_SCHEMA_DRY_RUN=true \
bash scripts/production-infrastructure-local-mysql-schema-apply.sh >/dev/null
nonlocal_status=$?
set -e

if [[ "$nonlocal_status" -eq 0 ]]; then
  printf 'schema apply should refuse non-local MYSQL_URL by default\n' >&2
  exit 1
fi

python3 - "$nonlocal_summary_file" <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if summary.get("status") != "REFUSED_NONLOCAL_MYSQL":
    raise SystemExit(f"expected non-local refusal: {summary.get('status')}")
PY

printf 'production infrastructure local mysql schema apply smoke test passed\n'
