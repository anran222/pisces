# Runtime Plane Release Evidence Archive

本文档定义运行时分流平面发布证据归档方式。归档对象是一次发布批次的可追溯证据，包括发布包检查报告、预发演练记录、容量基线 manifest、Redis 故障注入记录、事件管道 replay 审计 summary，以及可选的上一版发布批次 manifest 比对。

## 快速执行

生产发布前建议先在 CI 中通过 `Runtime Plane Release Package`，下载或引用 `runtime-plane-release-package-report` artifact，然后准备一份基于 `runtime-plane-preprod-drill-record-template.md` 填写的预发演练记录。归档前应执行 `scripts/runtime-plane-preprod-drill-record-check.sh` 做结构化校验；可参考 `runtime-plane-preprod-drill-record-sample.md` 的填写方式。

```bash
PISCES_RELEASE_ID="release-20260720-runtime-plane" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_PREPROD_DRILL_RECORD_FILE="docs/operations/releases/release-20260720-runtime-plane.md" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="target/pisces-runtime-baseline-archive/20260720T033000Z-preprod-exp_price_001-release-20260720-runtime-plane/manifest.json" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="target/pisces-event-pipeline-replay-audit/summary.json" \
bash scripts/runtime-plane-release-evidence-archive.sh
```

默认输出：

```text
target/pisces-runtime-release-evidence-archive/
  <timestamp>-<environment>-<releaseId>/
    manifest.json
    evidence/
      release-package-report.json
      preprod-drill-record.md
      capacity-baseline-manifest.json
      redis-fault-record
      event-pipeline-replay-audit-summary.json
      compare-manifest.json
```

## 生产默认门禁

脚本默认启用 `PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI=true`，会要求发布包报告满足：

- `status=PASS`
- `runTests=true`
- `requirePromtool=true`
- `requireRuby=true`

这意味着生产归档应使用 CI 严格模式产生的 `report.json`，而不是开发机默认静态检查报告。本地干跑可以临时设置：

```bash
PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI=false \
bash scripts/runtime-plane-release-evidence-archive.sh
```

## 预发记录校验

预发记录完成后先执行：

```bash
PISCES_PREPROD_DRILL_RECORD_FILE="<preprod-record.md>" \
PISCES_RELEASE_ID="<releaseId>" \
PISCES_EXPECTED_GIT_SHA="<git-sha>" \
PISCES_RELEASE_PACKAGE_REPORT_FILE="target/pisces-runtime-release-package-check/report.json" \
PISCES_CAPACITY_BASELINE_MANIFEST_FILE="<capacity-baseline-manifest.json>" \
PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE="<event-replay-audit-summary.json>" \
PISCES_PREPROD_REQUIRE_EVENT_REPLAY=true \
bash scripts/runtime-plane-preprod-drill-record-check.sh
```

发布证据归档完成并回填 manifest 路径和 sha256 后，再设置 `PISCES_PREPROD_REQUIRE_EVIDENCE_ARCHIVE=true` 复查。该校验会检查 releaseId、Git SHA、strict CI 门禁、runtime contract smoke、release drill、容量基线、Redis 故障、观测项、发布决策和可选事件 replay 证据。

## 本地验证

可用内置 smoke test 验证事件 replay audit summary 是否会进入发布证据 manifest：

```bash
bash scripts/runtime-plane-release-evidence-archive-smoke-test.sh
```

该测试会在 `target/pisces-runtime-release-evidence-archive-smoke/` 下构造最小发布包报告、预发演练记录和带分段修复的事件 replay audit summary，执行归档脚本，并断言 `eventPipelineReplayAudit.status=PASS`、`failedGateCount=0`、`repairSegmentIndex`、`segmentSummary.maxSegmentUnmaterializedCountBefore/After` 与 `evidence.eventPipelineReplayAuditSummary` 存在。默认 `scripts/runtime-plane-release-package-check.sh` 也会执行该 smoke test。

生产发布前置证据可用严格模式 smoke 验证：

```bash
bash scripts/runtime-plane-release-evidence-strict-smoke-test.sh
```

该测试会构造 `runTests=true`、`requirePromtool=true`、`requireRuby=true`、`gitDirty=false` 的发布包报告，同时归档容量基线 manifest、Redis 故障演练记录和分段 replay audit summary，并使用默认 `PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI=true` 与 `PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true` 执行归档脚本。通过该测试说明归档脚本能识别 CI 严格报告、干净代码版本、容量基线、Redis 故障记录和分段恢复证据。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_RELEASE_ID` | 无 | 必填，发布批次或变更 ID |
| `PISCES_RELEASE_EVIDENCE_ARCHIVE_DIR` | `target/pisces-runtime-release-evidence-archive` | 证据归档根目录 |
| `PISCES_RELEASE_PACKAGE_REPORT_FILE` | `target/pisces-runtime-release-package-check/report.json` | 发布包检查报告 |
| `PISCES_PREPROD_DRILL_RECORD_FILE` | 无 | 必填，预发演练记录 |
| `PISCES_CAPACITY_BASELINE_MANIFEST_FILE` | 空 | 可选，容量基线归档 manifest |
| `PISCES_REDIS_FAULT_RECORD_FILE` | 空 | 可选，Redis 故障注入记录 |
| `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` | 空 | 可选，事件管道 replay 审计 summary |
| `PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE` | 空 | 可选，用于比对的上一版或期望发布批次 manifest |
| `PISCES_ENVIRONMENT` | `preprod` | 环境名 |
| `PISCES_OPERATOR` | 当前系统用户 | 操作人 |
| `PISCES_EXPECTED_GIT_SHA` | 空 | 可选，要求发布包报告中的 Git SHA 与该值一致 |
| `PISCES_RELEASE_EVIDENCE_REQUIRE_PACKAGE_CI` | `true` | 是否要求发布包报告来自 CI 严格模式 |
| `PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT` | `false` | 是否要求发布包报告 `gitDirty=false` |

## Manifest 比对

设置 `PISCES_RELEASE_BATCH_COMPARE_MANIFEST_FILE` 后，脚本会把当前生成的 manifest 与指定 manifest 做稳定字段比对。比对字段包括：

- `releaseId`
- `environment`
- `releasePackage.status`
- `releasePackage.gitSha`
- `releasePackage.runTests`
- `releasePackage.requirePromtool`
- `releasePackage.requireRuby`
- `evidence.preprodDrillRecord.sha256`
- `evidence.capacityBaselineManifest.sha256`，仅当任一 manifest 包含该证据时检查
- `evidence.redisFaultRecord.sha256`，仅当任一 manifest 包含该证据时检查
- `evidence.eventPipelineReplayAuditSummary.sha256`，仅当任一 manifest 包含该证据时检查

`releasePackageReport` 的 SHA 不作为默认比对字段，因为报告内包含执行时间和检查计数，重跑同一版本时会自然变化。需要证明 CI 报告本身一致时，应在发布系统侧固定 artifact URL 和校验值。

## Event Replay Audit 摘要

当提供 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE` 时，manifest 会写入：

- `eventPipelineReplayAudit.status`、`experimentId`、`executeReplay`、`repairMaterialization`
- `eventPipelineReplayAudit.repairSegmentIndex`
- `eventPipelineReplayAudit.replayScopeRequest`
- `eventPipelineReplayAudit.failedGateCount`
- `eventPipelineReplayAudit.segmentSummary.segmentGateStatus`
- `eventPipelineReplayAudit.segmentSummary.requestedSegmentCount` / `segmentCount`
- `eventPipelineReplayAudit.segmentSummary.maxSegmentAffectedCount`
- `eventPipelineReplayAudit.segmentSummary.maxSegmentUnmaterializedCountBefore/After`

如果 audit summary 指定了 `repairSegmentIndex`，归档脚本会要求 `replayScopeRequest.segmentCount > 1`、`replay_plan_segments_generated=PASS` 和 `replayPlan.segments` 存在，避免只归档“已修复”结论而缺失分段恢复证据。

## 归档要求

- 预发演练记录必须包含 `Release Package Report`、`Decision` 和当前 `PISCES_RELEASE_ID`。
- 热路径、Redis 缓存、配置广播或 SDK 缓存策略有变化时，应同时提供容量基线 manifest 和 Redis 故障注入记录。
- 事件采集、异步物化、统计派生数据或 MAB 奖励有变化时，应同时提供 `PISCES_EVENT_REPLAY_AUDIT_SUMMARY_FILE`，且归档 manifest 中 `eventPipelineReplayAudit.status` 必须为 `PASS`。涉及大窗口补物化时应同时归档 `segmentSummary`，用修复前后最大分段缺账本数证明恢复范围没有扩大。
- 生产发布前应使用 CI 严格模式生成发布包报告：`runTests=true`、`requirePromtool=true`、`requireRuby=true`，并在需要强制干净版本时设置 `PISCES_RELEASE_EVIDENCE_REQUIRE_CLEAN_GIT=true`。
- 生产发布审批前，应把 `manifest.json` 和 `evidence/` 目录上传到发布系统或对象存储，并在变更单中记录归档地址。
