#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_env_file="${PISCES_LOCAL_ENV_FILE:-$repo_root/config/pisces-local.env}"
stack_env_file="${PISCES_LOCAL_STACK_ENV_FILE:-$repo_root/config/pisces-local-stack.env}"

load_env_file() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

resolve_key_for_scope() {
  local scope="$1"
  python3 - "${PISCES_API_KEY_SPECS:-}" "$scope" <<'PY'
import sys

specs, required_scope = sys.argv[1:]
for raw_spec in specs.split(","):
    parts = [part.strip() for part in raw_spec.split("|")]
    if len(parts) < 4:
        continue
    scopes = {scope.strip().lower() for scope in parts[3].split("+") if scope.strip()}
    if required_scope.lower() in scopes or "admin" in scopes:
        print(parts[0])
        raise SystemExit(0)
raise SystemExit(1)
PY
}

load_env_file "$stack_env_file"
load_env_file "$local_env_file"

instance_urls="${PISCES_INSTANCE_URLS:-http://127.0.0.1:9990/api}"
api_base="${instance_urls%%,*}"
api_base="${api_base%/}"
management_key="${PISCES_MANAGEMENT_API_KEY:-}"
analysis_key="${PISCES_ANALYSIS_API_KEY:-}"
runtime_key="${PISCES_RUNTIME_API_KEY:-}"
approval_key="${PISCES_ADMIN_API_KEY:-}"

if [[ -z "$management_key" ]]; then
  management_key="$(resolve_key_for_scope management)"
fi
if [[ -z "$analysis_key" ]]; then
  analysis_key="$(resolve_key_for_scope analysis)"
fi
if [[ -z "$runtime_key" ]]; then
  runtime_key="$(resolve_key_for_scope runtime)"
fi
if [[ -z "$approval_key" ]]; then
  approval_key="$(resolve_key_for_scope admin || true)"
fi

output_file="${PISCES_WORKFLOW_SMOKE_OUTPUT_FILE:-$repo_root/target/pisces-local-experiment-workflow-smoke/summary.json}"
mkdir -p "$(dirname "$output_file")"

python3 - "$api_base" "$management_key" "$analysis_key" "$runtime_key" "$approval_key" "$output_file" <<'PY'
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta
from pathlib import Path


api_base, management_key, analysis_key, runtime_key, approval_key, output_path = sys.argv[1:]
output_file = Path(output_path)
experiment_id = None
experiment_started = False
experiment_stopped = False
steps = []


def record(name, **details):
    steps.append({"name": name, "status": "PASS", **details})


def request(method, path, api_key, body=None, allow_error=False, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{api_base}{path}",
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "X-Pisces-Api-Key": api_key,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return response.status, payload
    except urllib.error.HTTPError as error:
        raw_body = error.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw_body)
        except json.JSONDecodeError:
            payload = {"message": raw_body or str(error)}
        if allow_error:
            return error.code, payload
        raise RuntimeError(
            f"{method} {path} failed with HTTP {error.code}: {payload.get('message', 'unknown error')}"
        ) from error


def unwrap(payload, operation):
    code = payload.get("code", 200)
    if code != 200:
        raise RuntimeError(f"{operation} failed: {payload.get('message', 'unknown error')}")
    return payload.get("data")


def clean_dictionary_item(item, allowed_fields):
    return {field: item.get(field) for field in allowed_fields if field in item}


def write_summary(status, **details):
    summary = {
        "summaryType": "pisces-local-experiment-workflow-smoke",
        "summaryVersion": 1,
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "status": status,
        "appId": "shop-app",
        "experimentId": experiment_id,
        "steps": steps,
        **details,
    }
    output_file.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


try:
    _, applications_payload = request("GET", "/applications", management_key)
    applications = unwrap(applications_payload, "查询应用") or []
    application = next((item for item in applications if item.get("appId") == "shop-app"), None)
    if application is None:
        raise RuntimeError("当前访问身份看不到 shop-app")
    record("应用可访问", displayName=application.get("displayName"))

    _, dictionary_payload = request("GET", "/applications/shop-app/dictionary", management_key)
    dictionary = unwrap(dictionary_payload, "查询应用字典") or {}
    source_events = dictionary.get("eventDefinitions") or []
    source_metrics = dictionary.get("metricDefinitions") or []
    if not source_events or not source_metrics:
        raise RuntimeError("shop-app 事件或指标字典为空")
    event_fields = {"key", "label", "description", "category", "primary"}
    metric_fields = {
        "key",
        "name",
        "description",
        "aggregationType",
        "numeratorEventType",
        "denominatorType",
        "denominatorEventType",
        "primaryMetric",
        "guardrailMetric",
    }
    primary_metric = next((item for item in source_metrics if item.get("primaryMetric")), source_metrics[0])
    selected_metrics = [primary_metric]
    selected_metrics.extend(
        item
        for item in source_metrics
        if item.get("guardrailMetric") and item.get("key") != primary_metric.get("key")
    )
    metric_definitions = [clean_dictionary_item(item, metric_fields) for item in selected_metrics]
    required_event_keys = {
        event_key
        for metric in selected_metrics
        for event_key in (metric.get("numeratorEventType"), metric.get("denominatorEventType"))
        if event_key
    }
    selected_events = [item for item in source_events if item.get("key") in required_event_keys]
    if not selected_events:
        raise RuntimeError("所选指标未关联到应用事件")
    preferred_primary_event = primary_metric.get("denominatorEventType") or primary_metric.get("numeratorEventType")
    event_definitions = []
    for item in selected_events:
        event_definition = clean_dictionary_item(item, event_fields)
        event_definition["primary"] = item.get("key") == preferred_primary_event
        event_definitions.append(event_definition)
    record("应用字典可用", eventCount=len(event_definitions), metricCount=len(metric_definitions))

    now = datetime.now().replace(microsecond=0)
    run_id = uuid.uuid4().hex[:8]
    create_request = {
        "name": f"二手手机质检保障完整链路验收-{run_id}",
        "description": "验证应用字典、实验配置、分流曝光、事件物化、统计报告与结论门禁的真实链路。",
        "appId": "shop-app",
        "owner": "anran",
        "layerId": f"workflow-smoke-{run_id}",
        "startTime": (now - timedelta(minutes=1)).isoformat(),
        "endTime": (now + timedelta(days=7)).isoformat(),
        "groups": [
            {
                "id": "control",
                "name": "原始商品卡",
                "trafficRatio": 0.5,
                "config": {
                    "headline": "官方质检二手手机",
                    "trustBadge": "平台保障",
                    "cta": "查看详情",
                },
            },
            {
                "id": "trust_variant",
                "name": "质检保障强化组",
                "trafficRatio": 0.5,
                "config": {
                    "headline": "99道质检，一年质保",
                    "trustBadge": "7天无理由退货",
                    "cta": "放心选购",
                },
            },
        ],
        "traffic": {
            "totalTraffic": 1.0,
            "strategy": "HASH",
            "hashKey": "visitorId",
            "allocation": [
                {"group": "control", "ratio": 0.5},
                {"group": "trust_variant", "ratio": 0.5},
            ],
        },
        "whitelist": [],
        "blacklist": [],
        "eventDefinitions": event_definitions,
        "metricDefinitions": metric_definitions,
        "groupConfigSchema": [
            {
                "key": "headline",
                "label": "主标题",
                "valueType": "STRING",
                "required": True,
                "description": "商品卡首屏标题",
                "defaultValue": "官方质检二手手机",
            },
            {
                "key": "trustBadge",
                "label": "保障标签",
                "valueType": "STRING",
                "required": True,
                "description": "商品保障说明",
                "defaultValue": "平台保障",
            },
            {
                "key": "cta",
                "label": "按钮文案",
                "valueType": "STRING",
                "required": True,
                "description": "商品卡主操作按钮",
                "defaultValue": "查看详情",
            },
        ],
    }

    _, preflight_payload = request("POST", "/experiments/preflight", management_key, create_request)
    preflight = unwrap(preflight_payload, "实验创建前检查") or {}
    if not preflight.get("readyToCreate") or preflight.get("blockingCount", 0) != 0:
        raise RuntimeError(f"实验创建前检查未通过: {preflight.get('checks', [])}")
    record("创建前检查", warningCount=preflight.get("warningCount", 0))

    _, create_payload = request("POST", "/experiments", management_key, create_request)
    created_experiment = unwrap(create_payload, "创建实验") or {}
    experiment_id = created_experiment.get("id")
    if not experiment_id:
        raise RuntimeError("创建实验后未返回实验 ID")
    record("创建实验", experimentId=experiment_id)

    if application.get("approvalRequired"):
        if not approval_key:
            raise RuntimeError("shop-app 启用了启动审批，但没有配置管理员验收密钥")
        request(
            "POST",
            f"/experiments/{experiment_id}/approval-status",
            approval_key,
            {
                "approvalStatus": "APPROVED",
                "operator": "admin",
                "comment": "本地完整流程自动验收通过启动审批。",
            },
        )
        record("启动审批")

    request("POST", f"/experiments/{experiment_id}/start", management_key)
    experiment_started = True
    record("启动实验")

    visitor_id = f"workflow-smoke-{run_id}"
    _, assignment_payload = request(
        "POST",
        "/traffic/assign/trace",
        runtime_key,
        {"experimentId": experiment_id, "visitorId": visitor_id, "attributes": {"scene": "acceptance"}},
    )
    assignment = unwrap(assignment_payload, "运行时分流") or {}
    if not assignment.get("groupId") or not assignment.get("configVersion"):
        raise RuntimeError("运行时分流缺少分组或配置版本")
    record("运行时分流", groupId=assignment.get("groupId"), configVersion=assignment.get("configVersion"))

    request(
        "POST",
        "/data/exposure",
        runtime_key,
        {"experimentId": experiment_id, "visitorId": visitor_id, "properties": {"source": "workflow-smoke"}},
    )
    request(
        "POST",
        "/data/event",
        runtime_key,
        {
            "experimentId": experiment_id,
            "visitorId": visitor_id,
            "eventType": "PRODUCT_DETAIL_VIEW",
            "eventName": "浏览商品详情",
            "properties": {"clientEventId": f"workflow-{run_id}-view", "source": "workflow-smoke"},
        },
    )
    record("曝光与事件上报")

    request(
        "POST",
        f"/experiments/generator/{experiment_id}/simulate",
        management_key,
        {"visitorCount": 120, "daysAgo": 3},
        timeout=180,
    )
    request(
        "POST",
        f"/analysis/experiment/{experiment_id}/event-pipeline/drain?operator=anran",
        analysis_key,
        timeout=180,
    )
    record("实验数据物化")

    _, statistics_payload = request(
        "GET", f"/analysis/experiment/{experiment_id}/statistics", analysis_key
    )
    statistics = unwrap(statistics_payload, "查询实验统计") or {}
    summary = statistics.get("summary") or {}
    quality = statistics.get("dataQualityCheck") or {}
    if summary.get("totalAssignments", 0) <= 0 or summary.get("totalEvents", 0) <= 0:
        raise RuntimeError("统计结果缺少真实分流或事件数据")
    if len(statistics.get("groupStatistics") or {}) != 2:
        raise RuntimeError("统计结果未覆盖两个实验组")
    record(
        "统计分析",
        totalAssignments=summary.get("totalAssignments", 0),
        totalExposures=summary.get("totalExposures", 0),
        totalEvents=summary.get("totalEvents", 0),
        analysisReady=bool(quality.get("analysisReady")),
    )

    _, snapshot_payload = request(
        "POST",
        f"/analysis/experiment/{experiment_id}/report/snapshots?generatedBy=anran",
        analysis_key,
        timeout=180,
    )
    snapshot = unwrap(snapshot_payload, "生成报告快照") or {}
    snapshot_version = snapshot.get("snapshotVersion")
    if not snapshot_version:
        raise RuntimeError("报告快照缺少版本号")
    _, report_payload = request("GET", f"/analysis/experiment/{experiment_id}/report", analysis_key)
    report = unwrap(report_payload, "导出实验报告") or {}
    if not report:
        raise RuntimeError("实验报告为空")
    record("报告快照与导出", snapshotVersion=snapshot_version)

    request("POST", f"/experiments/{experiment_id}/stop", management_key)
    experiment_stopped = True
    record("停止实验")

    _, detail_payload = request("GET", f"/experiments/{experiment_id}", management_key)
    detail = unwrap(detail_payload, "查询实验详情") or {}
    config_version = detail.get("configVersion")
    conclusion_request = {
        "conclusionStatus": "READY_FOR_REVIEW",
        "expectedConfigVersion": config_version,
        "reportSnapshotVersion": snapshot_version,
        "operator": "anran",
        "comment": "本地完整流程验收，基于当前配置和报告快照提交审核。",
    }
    conclusion_http_status, conclusion_payload = request(
        "POST",
        f"/experiments/{experiment_id}/conclusion-status",
        management_key,
        conclusion_request,
        allow_error=True,
    )
    conclusion_accepted = conclusion_http_status < 400 and conclusion_payload.get("code", 200) == 200
    if not conclusion_accepted and quality.get("analysisReady"):
        raise RuntimeError(
            f"数据已满足分析门禁，但人工结论提交失败: {conclusion_payload.get('message', 'unknown error')}"
        )
    record(
        "人工结论门禁",
        accepted=conclusion_accepted,
        outcome="已提交审核" if conclusion_accepted else "样本门禁按预期阻止提交",
    )

    request("DELETE", f"/experiments/{experiment_id}", management_key)
    experiment_id_for_summary = experiment_id
    experiment_id = None
    record("清理临时实验", cleanedExperimentId=experiment_id_for_summary)
    experiment_id = experiment_id_for_summary
    write_summary(
        "PASS",
        cleanedUp=True,
        analysisReady=bool(quality.get("analysisReady")),
        conclusionAccepted=conclusion_accepted,
    )
except Exception as error:
    cleanup_error = None
    cleanup_succeeded = experiment_id is None
    if experiment_id:
        try:
            if experiment_started and not experiment_stopped:
                request("POST", f"/experiments/{experiment_id}/stop", management_key, allow_error=True)
            cleanup_status, cleanup_payload = request(
                "DELETE", f"/experiments/{experiment_id}", management_key, allow_error=True
            )
            cleanup_succeeded = cleanup_status < 400 and cleanup_payload.get("code", 200) == 200
            if not cleanup_succeeded:
                cleanup_error = cleanup_payload.get("message", f"HTTP {cleanup_status}")
        except Exception as cleanup_exception:
            cleanup_error = str(cleanup_exception)
    write_summary("FAIL", error=str(error), cleanupError=cleanup_error, cleanedUp=cleanup_succeeded)
    raise
PY

printf 'local experiment workflow smoke passed: %s\n' "$output_file"
