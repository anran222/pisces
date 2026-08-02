# Runtime Plane Staged Rollout Decision

本文档用于运行时分流平面分批发布准入和回滚决策。它把发布包报告、发布证据 manifest、发布后 SLO 回看、实验影响面抽样和人工准入记录合并为一个结构化决策：`PROCEED`、`HOLD` 或 `ROLLBACK`。

脚本不访问线上服务，只读取已经生成的证据文件。因此它适合在 canary、ramp、full 和 post-release 观察窗口中重复执行。

## 执行

```bash
PISCES_RELEASE_STAGE="canary" \
PISCES_RELEASE_EVIDENCE_MANIFEST_FILE="target/pisces-runtime-release-evidence-archive/<release>/manifest.json" \
PISCES_POST_RELEASE_SLO_SUMMARY_FILE="target/pisces-runtime-post-release-slo-review/summary.json" \
PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE="target/pisces-runtime-experiment-impact-sampling/summary.json" \
PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE="docs/operations/runtime-plane-staged-rollout-acceptance-sample.json" \
PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT=10 \
PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT=10 \
bash scripts/runtime-plane-staged-rollout-decision.sh
```

默认输出：

```text
target/pisces-runtime-staged-rollout-decision/summary.json
```

退出码：

| 退出码 | 决策 | 说明 |
| --- | --- | --- |
| `0` | `PROCEED` | 可以进入下一阶段 |
| `1` | `HOLD` | 不推进流量，补齐证据或修复门禁 |
| `2` | `ROLLBACK` | 停止发布，执行回滚或止血，并进入异常复盘 |

## 输入证据

| 证据 | 来源 | 要求 |
| --- | --- | --- |
| Release Evidence Manifest | `scripts/runtime-plane-release-evidence-archive.sh` | `manifestType=pisces-runtime-plane-release-evidence` |
| Post-Release SLO Summary | `scripts/runtime-plane-post-release-slo-review.sh` | `status=PASS` |
| Experiment Impact Summary | `scripts/runtime-plane-experiment-impact-sampling.sh` | `status=PASS` |
| Rollout Acceptance Record | `docs/operations/runtime-plane-staged-rollout-acceptance-sample.json` | `decision=PROCEED`、审批人非空、回滚计划已演练 |

## 变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_RELEASE_STAGE` | `canary` | `preprod`、`canary`、`ramp`、`full` 或 `post-release` |
| `PISCES_RELEASE_EVIDENCE_MANIFEST_FILE` | 空 | 必填，发布证据 manifest |
| `PISCES_POST_RELEASE_SLO_SUMMARY_FILE` | 空 | SLO 回看摘要 |
| `PISCES_EXPERIMENT_IMPACT_SUMMARY_FILE` | 空 | 实验影响面抽样摘要 |
| `PISCES_ROLLOUT_ACCEPTANCE_RECORD_FILE` | 空 | 分批发布人工准入记录 |
| `PISCES_ROLLOUT_DECISION_OUTPUT_FILE` | `target/pisces-runtime-staged-rollout-decision/summary.json` | 输出路径 |
| `PISCES_ROLLOUT_REQUIRE_SLO` | `true` | 是否要求 SLO 摘要存在并通过 |
| `PISCES_ROLLOUT_REQUIRE_IMPACT` | `true` | 是否要求影响面抽样存在并通过 |
| `PISCES_ROLLOUT_REQUIRE_ACCEPTANCE` | `true` | 是否要求人工准入记录存在并通过 |
| `PISCES_ROLLOUT_REQUIRE_PACKAGE_CI` | `true` | 是否要求 release package 由测试模式且强制 promtool/ruby 生成 |
| `PISCES_ROLLOUT_REQUIRE_CLEAN_GIT` | `false` | 是否要求 release package `gitDirty=false` |
| `PISCES_ROLLOUT_REQUIRE_TRACE_SAMPLING` | `false` | 是否要求影响面抽样开启 trace |
| `PISCES_ROLLOUT_FAILURE_DECISION` | `auto` | SLO 或影响面失败时 `hold`、`rollback` 或按阶段自动判断 |
| `PISCES_ROLLOUT_TARGET_TRAFFIC_PERCENT` | 空 | 当前阶段目标流量比例 |
| `PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT` | 空 | 当前阶段允许的最大流量比例 |

`auto` 策略下，`preprod` 阶段失败会 `HOLD`；`canary`、`ramp`、`full` 和 `post-release` 阶段 SLO 或影响面失败会 `ROLLBACK`。

## 准入记录

准入记录使用 JSON，推荐复制 `docs/operations/runtime-plane-staged-rollout-acceptance-sample.json` 后填写真实发布信息。关键字段：

- `releaseId`、`environment`、`stage` 必须与证据 manifest 和脚本参数一致。
- `decision` 必须为 `PROCEED` 才能继续发布；`HOLD` 会阻止推进，`ROLLBACK` 会触发回滚决策。
- `approvedBy` 必须非空。
- `rollbackPlan.tested` 必须为 `true`。
- `targetTrafficPercent` 应与当前阶段流量比例一致，并受 `PISCES_ROLLOUT_MAX_TRAFFIC_PERCENT` 限制。

## 回滚触发

生产阶段满足任一条件时默认输出 `ROLLBACK`：

- 发布后 SLO 回看不是 `PASS`。
- 实验影响面抽样不是 `PASS`。
- 人工准入记录显式 `decision=ROLLBACK`。

满足任一条件时输出 `HOLD`：

- 必要证据文件缺失或 JSON 非法。
- release package 不是 CI 强门禁产物。
- 准入记录缺少审批人、阶段不匹配或回滚计划未演练。
- 目标流量比例超过当前阶段上限。

## 发布记录

每个发布阶段都应归档：

- `target/pisces-runtime-staged-rollout-decision/summary.json`
- 当前阶段准入记录 JSON。
- 如决策为 `ROLLBACK`，同步创建 `docs/operations/runtime-plane-post-release-incident-review-template.md` 的复盘记录。
