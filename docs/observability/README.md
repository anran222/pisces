# Pisces Observability Assets

本目录保存可直接接入生产监控系统的模板资产。当前覆盖审批升级告警 outbox、通道回执投递链路、运行时分流、配置变更广播，以及 SDK 本地指标接入示例。

## Prometheus Scrape

后端服务通过 Spring Actuator 暴露 Prometheus 指标：

```yaml
scrape_configs:
  - job_name: pisces
    metrics_path: /api/actuator/prometheus
    static_configs:
      - targets:
          - pisces-api:9990
```

本地验证：

```bash
curl "http://localhost:9990/api/actuator/prometheus" | grep -E "pisces_approval_escalation|pisces_traffic|pisces_event_replay"
```

分流热路径指标：

- `pisces_traffic_assignment_requests_total`：按 `result`、`source`、`reason` 统计分流请求结果。
- `pisces_traffic_assignment_latency_seconds`：按 `result`、`source` 统计分流耗时。
- `pisces_traffic_cache_events_total`：按 `operation`、`result` 统计 Redis 缓存命中、未命中、写入和异常。

事件重放 worker 指标：

- `pisces_event_replay_jobs_total`：按 `status` 统计 replay job 提交、拒绝、成功、失败和取消计数。
- `pisces_event_replay_duration_seconds`：按终态 `status` 统计 replay job 执行耗时。

配置变更广播指标：

- `pisces_config_change_broadcast_enabled`：Redis 跨实例配置广播是否启用。
- `pisces_config_change_broadcast_published_total`：按 `result` 统计广播发送结果。
- `pisces_config_change_broadcast_received_total`：按 `result` 统计广播接收、应用、忽略和非法消息。
- `pisces_config_change_broadcast_listener_errors_total`：本地 listener 处理远端广播失败次数。
- `pisces_config_change_broadcast_last_published_epoch_seconds` / `pisces_config_change_broadcast_last_received_epoch_seconds`：最近一次成功发送或接收时间。

这些指标不包含实验 ID 或访客 ID，避免 Prometheus 高基数标签。

SDK 本地指标接入：

- Java SDK 与 JS SDK 通过 `getMetricsSnapshot()` 暴露请求尝试、成功、失败、重试、stale fallback、配置缓存命中/未命中和版本检查计数。
- 接入示例见 `sdk-metrics-integration.md`。
- SDK 指标应按应用、服务、实例和 SDK 类型聚合，不要使用实验 ID 或访客 ID 作为 Prometheus 标签。

## Alert Rules

规则文件：

- `prometheus/pisces-approval-escalation-alerts.yml`
- `prometheus/pisces-runtime-plane-alerts.yml`
- `approval-escalation-runbook.md`
- `runtime-plane-runbook.md`
- `sdk-metrics-integration.md`

接入方式：

```yaml
rule_files:
  - docs/observability/prometheus/pisces-approval-escalation-alerts.yml
  - docs/observability/prometheus/pisces-runtime-plane-alerts.yml
```

审批升级规则覆盖：

- 通道回执进入 `DEAD`。
- outbox 活跃告警持续未送达。
- 指标刷新失败或刷新时间过旧。
- Prometheus 抓不到审批升级指标。
- dispatcher 已启用但 webhook 目标数为 0。

`PISCES_APPROVAL_ESCALATION_DISPATCH_ENABLED=false` 是默认配置，因此“dispatcher 关闭”不作为默认告警。若生产环境强制要求外部投递，可在规则文件中启用已注释的 `PiscesApprovalEscalationDispatcherDisabled` 模板。

运行时平面规则覆盖：

- 分流请求异常。
- 分流 Redis 缓存错误持续增长。
- 配置变更广播发送失败。
- 配置变更广播收到非法消息。
- 配置变更广播本地 listener 失败。

审批升级告警触发后的排查、恢复和演练流程见 `approval-escalation-runbook.md`。运行时分流和配置广播告警见 `runtime-plane-runbook.md`。SDK 指标接入见 `sdk-metrics-integration.md`。

## Grafana Dashboard

仪表盘文件：

- `grafana/pisces-approval-escalation-dashboard.json`
- `grafana/pisces-runtime-plane-dashboard.json`

导入后选择 Prometheus 数据源。面板覆盖：

- 打开中的审批升级告警数量。
- 活跃 outbox 未送达数量。
- 通道死信数量。
- 指标刷新健康度。
- outbox / delivery 状态趋势。
- dispatcher 启用状态和目标数量。
- 指标刷新失败增长。

运行时平面仪表盘覆盖：

- 分流错误率、吞吐和 P95/P99 延迟。
- Redis 分流缓存事件和错误。
- 配置变更广播启用状态、发送/接收结果、listener 错误和最近广播时间。

## Validation

变更监控资产后至少执行：

```bash
bash scripts/runtime-plane-release-package-check.sh
bash -n scripts/runtime-plane-experiment-impact-sampling.sh
bash -n scripts/runtime-plane-staged-rollout-decision.sh
bash -n scripts/event-pipeline-replay-audit.sh
ruby -e 'require "yaml"; YAML.load_file(ARGV[0])' docs/observability/prometheus/pisces-approval-escalation-alerts.yml
ruby -e 'require "yaml"; YAML.load_file(ARGV[0])' docs/observability/prometheus/pisces-runtime-plane-alerts.yml
python3 -m json.tool docs/observability/grafana/pisces-approval-escalation-dashboard.json >/dev/null
python3 -m json.tool docs/observability/grafana/pisces-runtime-plane-dashboard.json >/dev/null
```

如果本机安装了 `promtool`，再执行：

```bash
promtool check rules docs/observability/prometheus/pisces-approval-escalation-alerts.yml
promtool check rules docs/observability/prometheus/pisces-runtime-plane-alerts.yml
```
