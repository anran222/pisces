# Runtime Plane Experiment Impact Sampling

本文档用于发布后按实验维度抽样确认影响面。它回答三个问题：

- 目标实验在每个 Pisces 实例上的 runtime 配置版本是否一致。
- runtime config 与轻量 version 接口是否互相一致。
- 如显式开启 trace，合成访客命中的 `groupId`、`source`、`reason` 和 `configVersion` 是否可解释。

默认流程只调用只读接口，不会触发分流写入。`/traffic/assign/trace` 抽样必须显式开启，因为生产环境 trace 请求可能写入分流事实、缓存或业务侧曝光链路。

## 执行

只读抽样：

```bash
PISCES_INSTANCE_URLS="http://prod-a.example.com/api,http://prod-b.example.com/api" \
PISCES_EXPERIMENT_IDS="exp_a,exp_b" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

指定期望版本：

```bash
PISCES_INSTANCE_URLS="http://prod-a.example.com/api,http://prod-b.example.com/api" \
PISCES_EXPERIMENT_IDS="exp_a,exp_b" \
PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS="exp_a:12,exp_b:19" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

开启 trace 抽样：

```bash
PISCES_INSTANCE_URLS="http://prod-a.example.com/api,http://prod-b.example.com/api" \
PISCES_EXPERIMENT_IDS="exp_a" \
PISCES_RUNTIME_API_KEY="<runtime-key>" \
PISCES_IMPACT_TRACE_ENABLED=true \
PISCES_IMPACT_VISITOR_COUNT=40 \
PISCES_VISITOR_PREFIX="post-release-<releaseId>" \
bash scripts/runtime-plane-experiment-impact-sampling.sh
```

默认输出：

```text
target/pisces-runtime-experiment-impact-sampling/summary.json
```

## 门禁

脚本会输出 `status=PASS|FAIL`，任一 `FAIL` gate 都会导致脚本非零退出。

| Gate | 说明 |
| --- | --- |
| `config_request_success` | 每个实例都能返回 runtime config |
| `version_request_success` | 每个实例都能返回 version 检查结果 |
| `version_matches_config` | `config.configVersion` 与 `version.currentVersion` 一致 |
| `multi_instance_config_convergence` | 同一实验在所有实例上的 `configVersion` 收敛 |
| `runtime_config_group_count` | `groups` 数量不少于 `PISCES_IMPACT_MIN_GROUP_COUNT` |
| `expected_status` | 设置 `PISCES_IMPACT_EXPECTED_STATUS` 时校验实验状态 |
| `expected_config_version` | 设置 `PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS` 时校验目标版本 |
| `trace_request_error_rate` | 开启 trace 时，失败率不超过 `PISCES_IMPACT_MAX_ERROR_RATE` |
| `trace_config_version_matches_runtime_config` | trace 返回版本与该实例 runtime config 一致 |
| `trace_group_coverage` | 开启 trace 时，命中的实验组数不少于 `PISCES_IMPACT_MIN_TRACE_GROUP_COUNT` |

## 变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PISCES_INSTANCE_URLS` | `http://localhost:9990/api` | 逗号分隔的实例 base URL |
| `PISCES_EXPERIMENT_IDS` | 空 | 必填，逗号分隔实验 ID |
| `PISCES_RUNTIME_API_KEY` | `runtime-key` | runtime scope API Key |
| `PISCES_IMPACT_OUTPUT_FILE` | `target/pisces-runtime-experiment-impact-sampling/summary.json` | 输出文件 |
| `PISCES_IMPACT_VERSION_WAIT_MILLIS` | `0` | version 接口 `waitMillis` |
| `PISCES_IMPACT_EXPECTED_STATUS` | 空 | 可选状态期望，例如 `RUNNING` |
| `PISCES_IMPACT_EXPECTED_CONFIG_VERSIONS` | 空 | 可选版本期望，例如 `exp_a:12,exp_b:19` |
| `PISCES_IMPACT_MIN_GROUP_COUNT` | `1` | runtime config 最小实验组数 |
| `PISCES_IMPACT_TRACE_ENABLED` | `false` | 是否调用 `/traffic/assign/trace` |
| `PISCES_IMPACT_VISITOR_COUNT` | `20` | 每个实验的合成访客数 |
| `PISCES_IMPACT_MIN_TRACE_GROUP_COUNT` | `1` | trace 抽样最少命中实验组数 |
| `PISCES_IMPACT_MAX_ERROR_RATE` | `0` | trace 请求最大失败率 |
| `PISCES_VISITOR_PREFIX` | `impact-<epoch>` | 合成访客 ID 前缀 |
| `PISCES_IMPACT_TIMEOUT_SECONDS` | `10` | 单次 HTTP 超时 |

## 发布记录

把 `summary.json` 追加到发布记录，并记录：

- 抽样实验列表和实例列表。
- 是否开启 trace；如开启，记录访客前缀和样本数。
- `status`、失败 gate、失败实例。
- 若 SLO review 通过但影响面抽样失败，应进入 `docs/operations/runtime-plane-post-release-incident-review-template.md`，不能关闭发布观察。
