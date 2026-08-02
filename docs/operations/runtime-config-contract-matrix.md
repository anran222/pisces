# Runtime Config Contract Matrix

本文档定义 Pisces 运行时配置契约。契约覆盖 SDK 使用的两个 runtime 接口：

- `GET /api/runtime/experiments/{id}/config`
- `GET /api/runtime/experiments/{id}/config/version`

目标是保证配置发布、SDK 缓存、长轮询和多实例广播持续兼容。

## Config Contract

| 契约项 | 期望 | 自动化证据 |
| --- | --- | --- |
| 应用隔离 | runtime key 只能读取同 `appId` 实验，跨应用返回 `FORBIDDEN` | `RuntimeConfigServiceImplTest.getExperimentConfigShouldRejectOtherApp` |
| 基础字段 | 返回 `id`、`name`、`description`、`status`、`configVersion` | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |
| 实验组 | `groups` 按实验组 ID 输出，包含组名、流量比例和业务配置 | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |
| 空组配置 | 实验组 `config` 缺失时返回空 map，不返回 `null` | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnEmptyGroupConfigWhenGroupConfigIsMissing` |
| 事件定义 | `eventDefinitions` 保留事件 key、名称、分类和主事件标记 | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |
| 指标定义 | `metricDefinitions` 保留聚合类型、分子事件、分母类型和主/护栏标记 | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |
| 实验组 schema | `groupConfigSchema` 保留字段 key、类型、必填和默认值 | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |
| 可选集合 | `groups`、`eventDefinitions`、`metricDefinitions`、`groupConfigSchema` 缺失时返回空集合或空 map | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnEmptyCollectionsForOptionalRuntimeAssets`、`RuntimeConfigControllerContractTest.getExperimentConfigShouldPreserveEmptyCollectionsInHttpResponse` |
| 流量配置 | `traffic` 保留总流量、策略、hashKey 和 allocation | `RuntimeConfigServiceImplTest.getExperimentConfigShouldReturnRuntimeConfigForSameApp` |

## Version Contract

| 契约项 | 期望 | 自动化证据 |
| --- | --- | --- |
| 未变化 | `knownVersion == currentVersion` 时 `changed=false` | `RuntimeConfigServiceImplTest.getExperimentConfigVersionShouldReportChangeState` |
| 已变化 | `knownVersion != currentVersion` 或 `knownVersion` 为空时 `changed=true` | `RuntimeConfigServiceImplTest.getExperimentConfigVersionShouldReportChangeState` |
| 无 knownVersion | 不进入 long-poll，也不读取配置变更序列 | `RuntimeConfigServiceImplTest.getExperimentConfigVersionShouldNotWaitWhenKnownVersionIsMissing` |
| 当前版本等待 | `knownVersion` 为当前版本且 `waitMillis>0` 时进入 bounded long-poll | `RuntimeConfigServiceImplTest.getExperimentConfigVersionShouldWaitWhenKnownVersionIsCurrent` |
| 等待上限 | `waitMillis` 最大钳制到 30 秒 | `RuntimeConfigServiceImplTest.getExperimentConfigVersionShouldClampWaitMillis` |
| 应用隔离 | 版本检查也必须先通过应用隔离 | `RuntimeConfigServiceImplTest.getExperimentConfigShouldRejectOtherApp` |

## HTTP Contract

| 契约项 | 期望 | 自动化证据 |
| --- | --- | --- |
| 配置响应形状 | HTTP 响应包含 `BaseResponse.code/message/data`，`data` 保留基础字段、定义、组配置和流量配置 | `RuntimeConfigControllerContractTest.getExperimentConfigShouldReturnRuntimeContractShape` |
| 空集合序列化 | Service 返回空集合或空 map 时，HTTP JSON 仍输出 `[]` 或 `{}` | `RuntimeConfigControllerContractTest.getExperimentConfigShouldPreserveEmptyCollectionsInHttpResponse` |
| 版本 query 绑定 | `/config/version` 正确绑定 `knownVersion` 和 `waitMillis` | `RuntimeConfigControllerContractTest.getExperimentConfigVersionShouldBindKnownVersionAndWaitMillis` |
| 可选 query | 缺失 `knownVersion` 和 `waitMillis` 时传入 Service 的参数为 `null` | `RuntimeConfigControllerContractTest.getExperimentConfigVersionShouldAllowMissingOptionalQueryParams` |

## SDK Compatibility

| SDK 行为 | 依赖契约 | 自动化证据 |
| --- | --- | --- |
| TTL 过期先查版本 | `/config/version` 返回 `changed=false` 时续期本地快照 | Java SDK `shouldExtendExperimentCacheWhenExpiredVersionIsUnchanged`、JS SDK `should extend experiment cache when expired version is unchanged` |
| 长轮询参数 | SDK 可附加 `waitMillis` | Java SDK `shouldAppendConfigVersionLongPollMillisWhenCheckingExpiredCacheVersion`、JS SDK `should append config version long poll millis when checking expired cache version` |
| trace 版本刷新 | `assign/trace` 返回 `configVersion` 后 SDK 能刷新 runtime 配置 | Java SDK `shouldRefreshExperimentConfigWhenAssignmentVersionDiffers`、JS SDK `should refresh experiment config when assignment version differs` |
| stale fallback | 新配置拉取失败时只在旧快照包含命中组配置时回退 | Java SDK `shouldUseStaleGroupConfigWhenAssignmentVersionDiffersAndRefreshFails`、JS SDK `should use stale group config when assignment version differs and refresh fails` |

## 发布门禁

运行时配置契约变更必须满足：

1. 更新本矩阵中受影响的契约项。
2. 补充或更新对应自动化测试。
3. 运行 `mvn -pl pisces-api -am -Dtest=RuntimeConfigControllerContractTest -Dsurefire.failIfNoSpecifiedTests=false test`。
4. 运行 `mvn -pl pisces-service -am -Dtest=RuntimeConfigServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`。
5. 若 SDK 解析字段发生变化，运行 Java SDK 和 JS SDK 测试。
6. 运行 `bash scripts/runtime-plane-release-package-check.sh`。
7. 发布前按 `docs/operations/runtime-plane-release-checklist.md` 执行演练。
