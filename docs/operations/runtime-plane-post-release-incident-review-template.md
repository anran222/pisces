# Runtime Plane Post-Release Incident Review Template

本模板用于运行时分流平面发布后异常复盘。触发条件包括发布后 SLO 回看失败、实验级影响面抽样失败、分流错误持续增长、多实例配置版本不收敛、SDK stale fallback 异常增长，或业务侧确认实验结果异常。

## 1. Incident Metadata

| 字段 | 值 |
| --- | --- |
| Incident ID |  |
| Release ID |  |
| 发现时间 |  |
| 发现渠道 | 告警 / SLO review / impact sampling / 业务反馈 |
| 影响环境 |  |
| 负责人 |  |
| 当前状态 | Open / Mitigating / Resolved / Closed |

## 2. Evidence

| 证据 | 路径或链接 |
| --- | --- |
| Release Evidence Manifest |  |
| Post-Release SLO Summary |  |
| Experiment Impact Sampling Summary |  |
| Grafana Dashboard |  |
| Prometheus Query |  |
| SDK Metrics Snapshot |  |
| API 日志或 Trace |  |

## 3. Impact

| 项 | 值 |
| --- | --- |
| 受影响实验 |  |
| 受影响实例 |  |
| 受影响业务方 |  |
| 影响窗口 |  |
| 分流错误率 |  |
| P95 / P99 |  |
| SDK request failure / stale fallback |  |
| 用户或流量影响估计 |  |

## 4. Timeline

| 时间 | 事件 | 证据 |
| --- | --- | --- |
|  | 发布开始 |  |
|  | 异常出现 |  |
|  | 告警或抽样失败 |  |
|  | 止血动作 |  |
|  | 恢复确认 |  |

## 5. Root Cause

| 问题 | 结论 |
| --- | --- |
| 直接原因 |  |
| 触发条件 |  |
| 为什么预发未发现 |  |
| 为什么监控或门禁未提前阻断 |  |
| 是否存在同类实验或实例风险 |  |

## 6. Mitigation And Recovery

| 动作 | 负责人 | 时间 | 结果 |
| --- | --- | --- | --- |
| 回滚 / 暂停实验 / 切换配置 |  |  |  |
| 修复配置或代码 |  |  |  |
| 重新执行 SLO review |  |  |  |
| 重新执行 impact sampling |  |  |  |

## 7. Prevention

| 改进项 | 类型 | 负责人 | 截止时间 | 关闭标准 |
| --- | --- | --- | --- | --- |
|  | 门禁 / 监控 / 测试 / 文档 / 工具 |  |  |  |

## 8. Close Criteria

- [ ] 异常实验的 `scripts/runtime-plane-experiment-impact-sampling.sh` 摘要为 `status=PASS`。
- [ ] 发布后 `scripts/runtime-plane-post-release-slo-review.sh` 摘要为 `status=PASS`。
- [ ] `pisces_traffic_assignment_requests_total{result="ERROR"}` 不再增长。
- [ ] `pisces_traffic_cache_events_total{result="ERROR"}` 不再持续增长。
- [ ] SDK `requestFailureCount` 和 `staleExperimentConfigFallbackCount` 已回落。
- [ ] 所有预防项都有负责人、截止时间和可验证关闭标准。
