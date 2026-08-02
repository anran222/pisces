# Runtime Plane Baseline Archive

容量基线结果必须可追溯到环境、实验、实例、代码版本和发布批次。`scripts/runtime-plane-capacity-baseline.sh` 负责生成 JSONL，`scripts/runtime-plane-archive-baseline.sh` 负责校验并归档。

## 归档命令

```bash
PISCES_BASELINE_INPUT_FILE="target/pisces-runtime-capacity-baseline-20260720113000.jsonl" \
PISCES_BASELINE_ARCHIVE_DIR="target/pisces-runtime-baseline-archive" \
PISCES_ENVIRONMENT="pre" \
PISCES_EXPERIMENT_ID="exp_price_001" \
PISCES_RELEASE_ID="release-20260720-runtime-plane" \
PISCES_INSTANCE_URLS="http://pre-a.example.com/api,http://pre-b.example.com/api" \
PISCES_OPERATOR="ops-a" \
bash scripts/runtime-plane-archive-baseline.sh
```

归档目录格式：

```text
target/pisces-runtime-baseline-archive/
  20260720T033000Z-pre-exp_price_001-release-20260720-runtime-plane/
    capacity-baseline.jsonl
    manifest.json
```

## Manifest 字段

| 字段 | 含义 |
| --- | --- |
| `archivedAt` | UTC 归档时间 |
| `environment` | 环境名 |
| `experimentId` | 压测实验 ID |
| `releaseId` | 发布批次、变更单或手工标识 |
| `instanceUrls` | 本次压测覆盖的实例入口 |
| `operator` | 操作人 |
| `gitSha` | 当前仓库 Git SHA |
| `gitDirty` | 归档时工作树是否存在未提交变更 |
| `stepCount` | 压测阶梯数量 |
| `maxErrorRate` | 所有阶梯中的最大错误率 |
| `maxP95Ms` / `maxP99Ms` | 所有阶梯中的最大 P95/P99 |
| `steps` | 每个阶梯的请求数、并发数、错误率、延迟和版本分布 |

## 归档规则

- 每次影响运行时热路径、Redis 缓存、配置广播、鉴权或 SDK 解析的发布都要归档容量基线。
- 预发和生产应分别归档，不能用预发结果替代生产观察。
- JSONL 原始结果和 `manifest.json` 必须一起保留。
- 如果 `maxErrorRate` 或 `maxP95Ms` 超出发布门禁，归档仍要保留，并在发布记录里标记为失败基线。
- 不要把访客 ID、实验流量明细或业务敏感属性写入 manifest。

## 发布记录引用

发布记录至少引用：

- 归档目录路径。
- `manifest.json` 中的 `gitSha`、`maxErrorRate`、`maxP95Ms`、`maxP99Ms`。
- Redis 故障注入演练是否通过。
- 运行时 Grafana 仪表盘观察窗口。
