# Runtime Plane Rollback Decision Drill Template

本模板用于演练运行时分流平面在分批发布中如何从证据进入回滚决策。演练目标不是制造真实故障，而是验证证据、门禁、负责人和动作是否能在观察窗口内闭环。

## 1. Drill Metadata

| 字段 | 值 |
| --- | --- |
| Drill ID |  |
| Release ID |  |
| Stage | canary / ramp / full / post-release |
| Environment |  |
| Operator |  |
| Drill time |  |
| Target traffic percent |  |

## 2. Evidence Inputs

| 证据 | 路径 |
| --- | --- |
| Release Evidence Manifest |  |
| Post-Release SLO Summary |  |
| Experiment Impact Sampling Summary |  |
| Staged Rollout Acceptance Record |  |
| Staged Rollout Decision Summary |  |

## 3. Drill Scenario

| 场景 | 预期决策 | 实际决策 | 证据 |
| --- | --- | --- | --- |
| 全部证据通过 | `PROCEED` |  |  |
| SLO 摘要失败 | `ROLLBACK` |  |  |
| 影响面抽样失败 | `ROLLBACK` |  |  |
| 准入记录缺审批人 | `HOLD` |  |  |
| 目标流量超过阶段上限 | `HOLD` |  |  |

## 4. Commands

正常准入：

```bash
PISCES_RELEASE_STAGE="canary" \
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="<slo-summary.json>" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="<impact-summary.json>" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="<acceptance-record.json>" \
PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT=10 \
PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT=10 \
bash scripts/runtime-plane-staged-rollout-decision.sh
```

强制回滚演练：

```bash
PISCES_RELEASE_STAGE="canary" \
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="<release-evidence-manifest.json>" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="<failed-slo-summary.json>" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="<impact-summary.json>" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="<acceptance-record.json>" \
bash scripts/runtime-plane-staged-rollout-decision.sh
```

## 5. Decision Review

| 问题 | 结论 |
| --- | --- |
| `ROLLBACK` 是否能在脚本退出码 `2` 中被 CI 或发布平台识别 |  |
| 回滚负责人是否明确 |  |
| 回滚命令或 runbook 是否可访问 |  |
| 回滚后是否会重新执行 SLO 回看和影响面抽样 |  |
| 是否需要更新发布门禁或告警阈值 |  |

## 6. Close Criteria

- [ ] `PROCEED`、`HOLD`、`ROLLBACK` 三类决策均已演练。
- [ ] 每类决策都有对应的 `summary.json` 证据。
- [ ] 回滚负责人和回滚计划记录在准入 JSON 中。
- [ ] 发布 checklist 已引用分批发布准入和回滚决策步骤。
