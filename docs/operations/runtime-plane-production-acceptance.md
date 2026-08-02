# Runtime Plane Production Acceptance

本文档用于生产发布最终验收。它把 release evidence manifest、发布后 SLO 回看、实验影响面抽样、分批发布决策和人工验收记录合并成一个结构化结果：`ACCEPT`、`HOLD` 或 `ROLLBACK`。

脚本不访问线上服务，只读取已经生成的证据文件。因此它适合在 full rollout 或 post-release 观察窗口结束后执行，并把输出归档为本次发布的最终验收摘要。

## 执行

```bash
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="target/pisces-runtime-release-evidence-archive/<release>/manifest.json" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="target/pisces-runtime-post-release-slo-review/summary.json" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="target/pisces-runtime-experiment-impact-sampling/summary.json" \
PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE="target/pisces-runtime-staged-rollout-decision/summary.json" \
PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE="docs/operations/runtime-plane-production-acceptance-sample.json" \
bash scripts/runtime-plane-production-acceptance-check.sh
```

默认输出：

```text
target/pisces-runtime-production-acceptance/summary.json
```

退出码：

| 退出码 | 决策 | 说明 |
| --- | --- | --- |
| `0` | `ACCEPT` | 生产验收通过，可关闭发布 |
| `1` | `HOLD` | 证据缺失或门禁不完整，不关闭发布 |
| `2` | `ROLLBACK` | 生产健康证据失败或人工决策回滚 |

## 输入证据

| 证据 | 来源 | 要求 |
| --- | --- | --- |
| Release Evidence Manifest | `scripts/runtime-plane-release-evidence-archive.sh` | `manifestType=pisces-runtime-plane-release-evidence`，发布包 `status=PASS` |
| Post-Release SLO Summary | `scripts/runtime-plane-post-release-slo-review.sh` | `status=PASS`，无失败 gate |
| Experiment Impact Summary | `scripts/runtime-plane-experiment-impact-sampling.sh` | `status=PASS`，无失败 gate |
| Staged Rollout Decision Summary | `scripts/runtime-plane-staged-rollout-decision.sh` | `decision=PROCEED` |
| Production Acceptance Record | `docs/operations/runtime-plane-production-acceptance-sample.json` | `finalDecision=ACCEPT`，审批人非空，回滚计划已演练 |

## 变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_RELEASE_EVIDENCE_MANIFEST_FILE` | 空 | 必填，发布证据 manifest |
| `PISCES_POST_RELEASE_SLO_SUMMARY_FILE` | 空 | 必填，发布后 SLO 摘要 |
| `PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE` | 空 | 必填，实验影响面抽样摘要 |
| `PISCES_STAGED_ROLLOUT_DECISION_SUMMARY_FILE` | 空 | 必填，分批发布决策摘要 |
| `PISCES_PRODUCTION_ACCEPTANCE_RECORD_FILE` | 空 | 必填，生产验收人工签收记录 |
| `PISCES_PRODUCTION_ACCEPTANCE_OUTPUT_FILE` | `target/pisces-runtime-production-acceptance/summary.json` | 输出路径 |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_PACKAGE_CI` | `true` | 是否要求 strict CI 发布包报告 |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CLEAN_GIT` | `false` | 是否要求发布包报告 `gitDirty=false` |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_CAPACITY` | `true` | 是否要求容量基线证据 |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_REDIS_FAULT` | `true` | 是否要求 Redis 故障演练证据 |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY` | `false` | 是否要求事件 replay audit 证据 |
| `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE` | `false` | 是否要求影响面抽样开启 trace |

如果本次变更影响事件采集、异步物化、统计派生数据或 MAB 奖励，应设置 `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_EVENT_REPLAY=true`。如果生产验收需要证明实际分流结果，应设置 `PISCES_PRODUCTION_ACCEPTANCE_REQUIRE_TRACE=true`。

## 验收记录

生产验收记录使用 JSON，推荐复制 `docs/operations/runtime-plane-production-acceptance-sample.json` 后填写真实发布信息。关键字段：

- `releaseId`、`environment` 必须与 release evidence manifest 一致。
- `stage` 必须与 staged rollout decision summary 一致。
- `finalDecision` 必须为 `ACCEPT` 才能关闭发布。
- `approvedBy` 必须非空。
- `rollbackPlan.tested` 必须为 `true`。
- `evidence` 中必须记录四类输入证据路径。

## 本地验证

可用内置 smoke test 验证完整验收包：

```bash
bash scripts/runtime-plane-production-acceptance-smoke-test.sh
```

该测试会构造 strict CI 发布包报告、容量基线、Redis 故障记录、分段 event replay audit、SLO 摘要、影响面抽样摘要、分批发布决策和生产验收记录，并断言最终输出 `decision=ACCEPT`。
