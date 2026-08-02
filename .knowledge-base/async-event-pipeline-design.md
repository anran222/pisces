# 异步事件管道设计

## 1. 背景

当前 `/data/event` 和 `/data/exposure` 在 `DataServiceImpl` 内同步完成分组解析、事实表写入、Redis 计数、访客集合更新和 MAB 奖励更新。P1 已完成 `clientEventId` 幂等写入，但采集入口仍与后续派生处理耦合。阶段二需要把采集入口演进为快速受理入口，让原始事件可重试、可追踪、可重放，指标结果可通过重放修正。

## 2. 现状分析

### 2.1 当前调用链路

```text
接入方 / SDK
  -> DataController.reportEvent / reportExposure
  -> DataServiceImpl.reportEvent / reportExposure
  -> TrafficService.getUserGroup
  -> ExperimentEventRepository / ExperimentExposureRepository
  -> MySQL 事实表
  -> Redis 事件列表、事件计数、访客集合、曝光集合
  -> MultiArmedBanditService.updateReward
```

### 2.2 现有代码与数据结构

| 类型 | 名称 | 作用 | 现状说明 |
|---|---|---|---|
| 接口 | `POST /data/event` | 事件采集 | 请求体为 `EventReportRequest`，`clientEventId` 位于 `properties` |
| 接口 | `POST /data/exposure` | 曝光采集 | 请求体为 `ExposureReportRequest`，按 `visitorId` 记录曝光 |
| 类 | `DataServiceImpl` | 数据采集编排 | 同步写事实表、Redis 和 MAB |
| 类 | `ExperimentEventRepository` | 事件事实仓库 | `saveIfAbsent` 使用数据库唯一键做幂等插入 |
| 表 | `pisces_experiment_event` | 事件事实表 | 唯一键包含 `event_id`、`experiment_id + client_event_id` |
| 表 | `pisces_experiment_exposure` | 曝光事实表 | 通过 `idempotency_key` 保证同一访客实验组曝光幂等 |

### 2.3 当前问题

| 问题 | 现象 | 原因 | 影响 |
|---|---|---|---|
| 采集入口处理链路长 | 请求线程承担事实写入、Redis 更新、MAB 奖励 | 入口与派生处理在同一方法内 | 入口延迟和依赖失败互相放大 |
| 原始事件缺少处理状态 | 事实表只保存已处理成功事件 | 没有 inbox 状态机 | 失败事件不可重试、不可定位处理阶段 |
| 指标回算缺少统一入口 | 统计直接读事实表和 Redis 派生数据 | 没有重放任务边界 | 修复历史数据需要临时脚本 |

### 2.4 可复用能力与改造点

| 类型 | 内容 | 说明 |
|---|---|---|
| 可复用能力 | `clientEventId` 幂等 | 作为事件 inbox 的业务幂等键 |
| 可复用能力 | `ExperimentEventFact` / `ExperimentExposure` | 作为消费者落库后的事实模型 |
| 可复用能力 | `AnalysisServiceImpl` | 继续从事实表聚合统计 |
| 改造点 | `DataServiceImpl` | 拆分为入口受理与消费者处理 |
| 改造点 | SQL | 新增原始事件 inbox 表和处理状态索引 |

## 3. 功能清单

| 功能编号 | 功能名称 | 功能说明 | 涉及模块 |
|---|---|---|---|
| F1 | 原始事件受理 | `/data/event` 与 `/data/exposure` 只做校验、分组解析、幂等受理和快速返回 | `pisces-api`、`pisces-service` |
| F2 | 事件消费者处理 | 后台消费者从 inbox 拉取事件，写事实表、Redis 派生数据和 MAB 奖励 | `pisces-service` |
| F3 | 重试与死信 | 失败事件按状态机重试，超过阈值进入死信状态 | `pisces-service`、MySQL |
| F4 | 重放与回算 | 支持按实验维度重新投递原始事件，重建事实与派生计数 | `pisces-service` |
| F5 | 管道可观测性 | 输出待处理、失败、死信、处理延迟等状态，供详情页和排障使用 | `pisces-api`、`pisces-web` |

## 4. 功能设计

### 4.1 F1 原始事件受理

处理流程：

```text
1. Controller 接收事件或曝光请求并完成参数校验。
2. DataService 解析 canonicalVisitorId，并通过 TrafficService 获取 groupId。
3. 构造 EventInboxRecord，写入 inbox 表。
4. 命中 inbox 幂等唯一键时直接返回成功。
5. groupId 为空时写入 REJECTED 状态和拒绝原因，不进入消费者事实处理。
```

模块职责：

| 模块 | 职责 | 输入 | 输出 |
|---|---|---|---|
| `DataController` | 保持现有 REST 契约 | `EventReportRequest` / `ExposureReportRequest` | `BaseResponse<Void>` |
| `DataServiceImpl` | 入口校验、分组解析、构造 inbox 记录 | 请求字段、实验配置、分流结果 | inbox 写入结果 |
| `EventInboxRepository` | 幂等受理原始事件 | `EventInboxRecord` | 是否新受理 |

关键规则：

| 规则 | 说明 | 异常处理 |
|---|---|---|
| 事件幂等键 | `EVENT:{experimentId}:{clientEventId}`，空 `clientEventId` 使用服务端生成 `eventId` | 重复键返回成功，不重复入队 |
| 曝光幂等键 | `EXPOSURE:{experimentId}:{visitorId}:{groupId}` | 重复曝光返回成功，不重复入队 |
| 分组缺失 | `TrafficService.getUserGroup` 返回空时记录 `REJECTED` | 不进入消费者，不影响统计 |

### 4.2 F2 事件消费者处理

处理流程：

```text
1. 定时任务按 next_retry_at 拉取 PENDING / RETRY 状态记录。
2. 抢占记录为 PROCESSING，并设置 locked_by、locked_until。
3. 根据 event_kind 分发到事件处理器或曝光处理器。
4. 事件处理器写入事实表、Redis 事件列表、Redis 计数、访客集合、MAB 奖励。
5. 曝光处理器写入曝光事实表、Redis 曝光列表、曝光集合。
6. 处理成功后标记 DONE；处理失败后交给 F3。
```

模块职责：

| 模块 | 职责 | 输入 | 输出 |
|---|---|---|---|
| `EventInboxConsumer` | 批量拉取和状态推进 | inbox 记录 | 处理结果 |
| `EventMaterializer` | 事件事实和派生数据落地 | `EventInboxRecord` | 事实表、Redis、MAB |
| `ExposureMaterializer` | 曝光事实和派生数据落地 | `EventInboxRecord` | 曝光表、Redis |

关键规则：

| 规则 | 说明 | 异常处理 |
|---|---|---|
| 批量大小 | 每轮按固定批量拉取，避免长事务 | 单条失败不影响同批其他记录 |
| 锁超时 | `locked_until < now()` 的 PROCESSING 记录可重新抢占 | 避免进程退出后永久卡住 |
| 派生副作用 | 事实表写入成功后再更新 Redis 和 MAB | 派生失败进入重试，重复处理由事实表幂等保护 |
| MAB 奖励观测 | 主 `RATE + EVENT_COUNT` 指标的 denominator 事件记录失败，numerator 事件按同一观测键升级为成功；观测键优先使用 `properties.mabObservationId`，其次使用 `visitorId`、`clientEventId`、`eventId` | 同一观测键重复失败不重复计数，成功不会被后续失败降级 |

### 4.3 F3 重试与死信

处理流程：

```text
1. 消费失败时记录 last_error。
2. retry_count + 1。
3. retry_count 小于阈值时状态改为 RETRY，并按指数退避设置 next_retry_at。
4. retry_count 达到阈值时状态改为 DEAD。
```

关键规则：

| 规则 | 说明 | 异常处理 |
|---|---|---|
| 最大重试 | 初始值 5 次 | 进入 DEAD 后不再自动消费 |
| 退避策略 | 1m、5m、15m、1h、6h | 每次失败更新 `next_retry_at` |
| 死信处理 | 管理接口只允许按实验维度重投 DEAD 记录 | 重投前保留原始错误和重投次数 |

### 4.4 F4 重放与回算

处理流程：

```text
1. 管理端发起指定 experimentId 的 replay。
2. 系统创建 replayJob，记录操作人、时间范围和事件类型。
3. 将符合条件的 DONE / DEAD inbox 记录复制为 REPLAY_PENDING。
4. 消费者按正常物化流程重建事实表和 Redis 派生数据。
5. 回放完成后刷新统计视图和报告快照。
```

当前落地口径：

- 已提供 `POST /analysis/experiment/{id}/events/replay`。
- 当前 replay 会创建 `pisces_event_replay_job` 记录并提交后台 worker，接口响应返回 `REPLAY_DERIVED/RUNNING`、`replayJobId`、`replayJobStatus=RUNNING` 和重放 scope；任务状态按 `RUNNING -> SUCCEEDED / FAILED` 或 `RUNNING -> CANCEL_REQUESTED -> CANCELLED` 推进。
- 已提供 `GET /analysis/experiment/{id}/events/replay/jobs` 和 `GET /analysis/experiment/{id}/events/replay/jobs/{replayJobId}` 查询 replay job，供管理台审计最近一次或指定任务的状态、操作者、重放模式、scope、计划事实数、运行中进度百分比、影响行数、事件/曝光/MAB 重建计数和失败原因。
- 已提供 `POST /analysis/experiment/{id}/events/replay/jobs/{replayJobId}/cancel` 请求取消运行中的 replay job；取消请求先写为 `CANCEL_REQUESTED` 并保留 `active_key`，后台 worker 在一致性安全点转为 `CANCELLED` 并释放互斥键；任务完成、失败或已取消后不允许再次取消，`CANCEL_REQUESTED` 重复取消保持幂等成功。
- 已提供 `POST /analysis/experiment/{id}/events/replay/plan` 生成只读计划，支持 `startTime`、`endTime`、`eventTypes`、`includeEvents`、`includeExposures` 统计匹配事实数、分组明细、已物化事实数和缺账本事实数，不修改 Redis/MAB 派生数据；筛选计划可对应 `FILTERED_DERIVED_COPY_REPLAY` 复制型执行。请求带 `segmentCount > 1` 且同时指定 `startTime/endTime` 时，会返回时间分段 `segments`、最大单段影响面和最大单段缺口，用于分区巡检。
- 已提供 `POST /analysis/experiment/{id}/events/replay/materialization/repair` 按安全边界修复缺失派生物化账本；无缺口时 no-op，等价全量计划存在缺口时调用全量派生重放，筛选计划存在缺口时按缺账本事实做局部补物化，且会先按 `eventId` / `exposureId` 检查 Redis 列表，避免重复写入派生计数。已提供 `/events/replay/materialization/repair/segments/{segmentIndex}`，可使用计划响应里的 0 基分段序号只恢复单个时间分段，适合失败后重试局部窗口。
- 同一实验 replay 使用 `active_key=experimentId` 的唯一约束做并发互斥；已结束或已取消任务会清空 `active_key`，取消中的任务继续持有互斥键，超时 `RUNNING` / `CANCEL_REQUESTED` 任务会按 `pisces.event-pipeline.replay.job-timeout-minutes` 过期为失败后允许重新发起。任务创建时会写入 `plannedAffectedCount` / `plannedEventCount` / `plannedExposureCount` / `plannedGroupCount`，运行中 `progressPercent` 由已处理事实数和计划事实数计算。
- 当前 replay 不复制 inbox 记录；它以事实表为准，等价全量范围按实验组清理并重建 Redis 事件列表、事件计数、访客集合、曝光列表、曝光集合、MAB 奖励和 MAB 奖励观测去重状态；筛选范围执行复制型 replay，不清空现有派生数据，只在 Redis 列表未包含同一 `eventId` / `exposureId` 时补写派生并刷新账本。
- 全量口径用于修复“事实表写入成功，但 Redis/MAB 派生数据整体漂移”的场景；筛选复制型 replay 用于窗口化补派生和修复局部缺失，不能证明既有 MAB 奖励状态无漂移。
- 已新增 `pisces_event_materialization` 事实派生物化账本，事件/曝光事实成功写入 Redis/MAB 派生后记录 `factKind + factId`、来源和 replay job；当事实写入成功但派生失败后重试，消费者会先按 `experimentId + clientEventId` 或曝光幂等键回查事实表中的真实事实 ID，再在账本缺失时补写派生数据，避免“事实已存在”直接跳过派生，也避免复制型 replay 用新 inbox ID 写出不存在的事实账本。
- 筛选复制型 replay 以 Redis 列表中的事实 ID 作为派生是否存在的幂等边界，避免重复写事件列表、曝光列表、事件计数和 MAB 奖励；广义 Redis/MAB 派生漂移仍需走全量 replay。
- 基于 inbox 复制的 replay 和清理后可立即停止的分阶段重建仍属于后续治理增强；当前全量派生重建如果已经进入破坏性清理阶段，会优先完成一致性重建后再落 `CANCELLED` 终态。

关键规则：

| 规则 | 说明 | 异常处理 |
|---|---|---|
| 重放前置 | 先按实验维度清理可重建派生数据 | 清理与重放 job 状态绑定 |
| 重放幂等 | replay 记录使用 `replayJobId + originalInboxId` 唯一键 | 同一个 job 重试不重复复制 |
| 事实幂等 | 事件事实继续依赖 `experimentId + clientEventId` | 重放不会制造重复事件 |

### 4.5 F5 管道可观测性

处理流程：

```text
1. 后端按 experimentId 聚合 inbox 状态。
2. 返回 pending、processing、retry、dead、rejected、done 数量和最大延迟。
3. 前端在数据链路状态组件中补充事件管道状态。
```

关键规则：

| 规则 | 说明 | 异常处理 |
|---|---|---|
| 状态展示 | `DEAD > RETRY > PENDING > DONE` 决定管道健康等级 | 无 inbox 数据时展示暂无采集 |
| 延迟口径 | `now - accepted_at` 的最大值作为积压延迟 | 仅统计未完成状态 |

## 5. 数据、接口与依赖设计

### 5.1 数据设计

| 功能编号 | 表 / 实体 | 变更类型 | 字段 / 索引 | 说明 |
|---|---|---|---|---|
| F1-F5 | `pisces_event_inbox` | 新增 | 原始事件、状态、幂等键、锁、重试、错误 | 采集入口和消费者之间的内部事件日志 |
| F4 | `pisces_event_replay_job` | 新增 | job 状态、实验 ID、操作者、重放模式、时间范围、事件类型、事件/曝光开关、是否全量重建、计划事实/组数、影响行数、事件/曝光/组/MAB 重建计数、活跃互斥键、错误信息 | 管理事实表派生重放任务、审计 scope、运行中进度和同实验并发互斥 |
| F2-F4 | `pisces_event_materialization` | 新增 | 事实类型、事实 ID、实验组、物化来源、replay job、物化时间 | 记录事实是否已成功写入 Redis/MAB 派生，用于消费重试恢复和后续筛选重放审计 |

```sql
CREATE TABLE IF NOT EXISTS pisces_event_inbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    inbox_id VARCHAR(64) NOT NULL COMMENT '内部事件ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    visitor_id VARCHAR(128) NOT NULL COMMENT '访客ID',
    group_id VARCHAR(64) DEFAULT NULL COMMENT '实验组ID',
    event_kind VARCHAR(32) NOT NULL COMMENT 'EVENT / EXPOSURE',
    event_type VARCHAR(64) DEFAULT NULL COMMENT '事件类型',
    event_name VARCHAR(128) DEFAULT NULL COMMENT '事件名称',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '幂等键',
    payload_json LONGTEXT DEFAULT NULL COMMENT '原始请求JSON',
    status VARCHAR(32) NOT NULL COMMENT 'PENDING / PROCESSING / RETRY / DONE / DEAD / REJECTED / REPLAY_PENDING',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_at DATETIME NOT NULL COMMENT '下次处理时间',
    locked_by VARCHAR(128) DEFAULT NULL COMMENT '处理节点',
    locked_until DATETIME DEFAULT NULL COMMENT '锁过期时间',
    last_error VARCHAR(1024) DEFAULT NULL COMMENT '最后失败原因',
    accepted_at DATETIME NOT NULL COMMENT '受理时间',
    processed_at DATETIME DEFAULT NULL COMMENT '处理完成时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbox_id (inbox_id),
    UNIQUE KEY uk_inbox_idempotency (idempotency_key),
    KEY idx_inbox_status_retry (status, next_retry_at, id),
    KEY idx_inbox_experiment_status (experiment_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces事件采集inbox表';
```

### 5.2 接口设计

| 功能编号 | 接口名称 | 方法与路径 | 描述 | 请求参数 | 返回参数 | 错误处理 |
|---|---|---|---|---|---|---|
| F1 | 事件上报 | `POST /data/event` | 保持现有请求契约，改为受理入 inbox | `EventReportRequest` | `BaseResponse<Void>` | 参数校验失败返回现有校验错误 |
| F1 | 曝光上报 | `POST /data/exposure` | 保持现有请求契约，改为受理入 inbox | `ExposureReportRequest` | `BaseResponse<Void>` | 参数校验失败返回现有校验错误 |
| F3 | 死信重投 | `POST /analysis/experiment/{id}/event-pipeline/dead/retry` | 将实验 DEAD inbox 重置为 RETRY | operator | 操作结果 | 无 DEAD 时影响记录数为 0 |
| F3 | 同步物化 | `POST /analysis/experiment/{id}/event-pipeline/drain` | 按实验领取并物化当前 due inbox 记录 | operator | 操作结果 | 超出 drain 预算时返回 PARTIAL |
| F4 | 发起重放 | `POST /analysis/experiment/{id}/events/replay` | 提交后台任务按事实表重建 Redis/MAB 派生数据；全量范围清空重建，筛选范围复制型补派生，记录 replay job | operator、startTime、endTime、eventTypes、includeEvents、includeExposures | `REPLAY_DERIVED/RUNNING`、`replayJobId`、`replayJobStatus` | 同实验已有 `RUNNING` 或 `CANCEL_REQUESTED` replay 时返回冲突 |
| F4 | 生成重放计划 | `POST /analysis/experiment/{id}/events/replay/plan` | 只读统计重放范围内事实数和物化覆盖，可按时间范围生成 `segmentCount` 分段巡检 | startTime、endTime、eventTypes、includeEvents、includeExposures、segmentCount | 匹配事件/曝光/分组计数、已物化/缺账本计数、是否等价全量重建、segments、最大单段影响面 | 不修改 Redis/MAB 派生数据；分段只在同时指定 startTime/endTime 时生成 |
| F4 | 修复缺账本 | `POST /analysis/experiment/{id}/events/replay/materialization/repair` | 按安全边界修复缺失派生物化账本 | 重放计划请求、operator | `REPAIR_MATERIALIZATION` 操作结果 | 无缺口 no-op；筛选计划存在缺口时只补缺账本事实，不替代全量派生漂移修复 |
| F4 | 分段修复缺账本 | `POST /analysis/experiment/{id}/events/replay/materialization/repair/segments/{segmentIndex}` | 只修复重放计划中的一个时间分段 | 重放计划请求、segmentIndex、operator | `REPAIR_MATERIALIZATION` 操作结果和分段 scope | 适合大范围修复失败后只重试失败分段；segmentIndex 为 0 基 |
| F4 | 查询重放任务 | `GET /analysis/experiment/{id}/events/replay/jobs` | 查询最近 replay job | limit | 任务状态、操作者、重放模式、scope、计划总量、进度百分比、计数、错误和起止时间 | limit 服务端限制在 1-50 |
| F4 | 查询任务详情 | `GET /analysis/experiment/{id}/events/replay/jobs/{replayJobId}` | 查询指定 replay job | replayJobId | 任务状态、操作者、重放模式、scope、计划总量、进度百分比、计数、错误和起止时间 | 任务不存在返回 404 |
| F4 | 取消重放任务 | `POST /analysis/experiment/{id}/events/replay/jobs/{replayJobId}/cancel` | 请求取消运行中的 replay job | replayJobId、operator | `CANCEL_REPLAY_JOB` 操作结果、`CANCEL_REQUESTED` 状态和原 replay scope | `CANCEL_REQUESTED` 重复调用幂等成功；终态任务返回 409 |
| F5 | 查询管道状态 | `GET /analysis/experiment/{id}/event-pipeline` | 查询 inbox 状态聚合 | experimentId | 状态数量、最大延迟 | 实验不存在返回空状态 |

### 5.3 依赖设计

| 功能编号 | 依赖类型 | 依赖对象 | 交互方式 | 入参 | 出参 | 失败处理 |
|---|---|---|---|---|---|---|
| F1 | DB | `pisces_event_inbox` | 幂等插入 | inbox record | affected rows | 插入重复返回成功 |
| F2 | DB | 事实表 | 幂等写入 | event / exposure fact | affected rows | 重复事实视为成功 |
| F2 | Cache | Redis | 更新计数和集合 | experimentId、groupId、eventType | 写入结果 | 失败进入重试 |
| F2 | Service | MAB | 按观测键记录奖励 | experimentId、groupId、observationId、success | 是否实际改变奖励统计 | 失败进入重试 |

## 6. 关键技术设计

### 6.1 幂等设计

| 场景 | 幂等键 | 处理方式 |
|---|---|---|
| 事件受理 | `EVENT:{experimentId}:{clientEventId}` | inbox 唯一键；重复直接成功返回 |
| 缺少 `clientEventId` 的事件 | `EVENT:{experimentId}:{serverEventId}` | 不承诺业务重试幂等，只保证单次受理唯一 |
| 曝光受理 | `EXPOSURE:{experimentId}:{visitorId}:{groupId}` | inbox 唯一键；重复曝光直接成功返回 |
| 事件事实 | `experimentId + clientEventId` | 事实表唯一键；消费者重复执行不重复计数 |
| MAB 奖励观测 | `{experimentId}:{groupId}:{mabObservationId 或 visitorId}` | 分母事件只贡献一条失败观测，后续分子事件升级为成功，成功不降级 |
| 派生物化账本 | `factKind + factId` | 事实已存在时先查物化账本；账本存在则跳过派生，账本缺失则补写 Redis/MAB 派生并刷新账本 |
| 重放复制 | `replayJobId + originalInboxId` | 重放 job 内唯一，避免重复复制 |

### 6.2 事务与并发

| 场景 | 事务边界 | 并发风险 | 控制方式 |
|---|---|---|---|
| 入口受理 | 单条 inbox 插入 | 多请求同时重试同一事件 | 唯一键 + 幂等插入 |
| 消费抢占 | 批量状态更新 | 多消费者抢同一条记录 | `PROCESSING` 状态 + `locked_until` 条件更新 |
| 事实物化 | 单条事件处理 | 消费者崩溃后重试 | 事实表幂等 + inbox 状态重试 |
| 重放回算 | replay job 维度 | 清理派生数据与重放并发 | 同一实验同一时间只允许一个 RUNNING replay job |

### 6.3 兼容方案

| 兼容对象 | 兼容方式 | 说明 |
|---|---|---|
| 现有上报接口 | 请求和响应不变 | 接入方无需改调用代码 |
| 现有统计接口 | 继续读事实表 | 消费完成后的统计口径不变 |
| 现有 `clientEventId` | 继续从 `properties.clientEventId` 读取 | 空白值不参与业务幂等 |
| 当前单体部署 | 先使用 MySQL inbox + 定时消费者 | 不引入外部 MQ 运维依赖 |

## 7. 代码设计

### 7.1 功能实现总览

| 功能编号 | 功能名称 | 涉及服务 / 模块 | 主要改动 |
|---|---|---|---|
| F1 | 原始事件受理 | `pisces-service` | 新增 inbox model、repository、mapper、SQL，改造 `DataServiceImpl` |
| F2 | 事件消费者处理 | `pisces-service` | 新增 consumer 和 materializer，复用现有事实仓库 |
| F3 | 重试与死信 | `pisces-service` | 新增状态流转和 retry 计算 |
| F4 | 重放与回算 | `pisces-service`、`pisces-api` | 已新增管理接口、后台 worker、按事实表重建派生数据、筛选复制型 replay、replay job 状态记录、取消请求、并发互斥和最近任务查询 |
| F5 | 管道可观测性 | `pisces-service`、`pisces-api`、`pisces-web` | 新增状态聚合接口，前端链路组件展示管道状态 |

### 7.2 主要改动文件

| 文件 / 类 | 改动类型 | 说明 |
|---|---|---|
| `EventInboxRecord` | 新增 | inbox 领域模型 |
| `EventInboxEntity` | 新增 | `pisces_event_inbox` 数据库实体 |
| `EventInboxRepository` | 新增 | inbox 受理、抢占、状态更新、状态聚合 |
| `EventInboxMapper.xml` | 新增 | inbox 插入、拉取、更新、聚合 SQL |
| `DataServiceImpl` | 修改 | `reportEvent` / `reportExposure` 改为写 inbox |
| `EventInboxConsumer` | 新增 | 定时批量消费 inbox |
| `EventMaterializer` | 新增 | 写事件事实表、Redis、MAB，并按主 RATE 指标识别 denominator 失败和 numerator 成功升级 |
| `ExposureMaterializer` | 新增 | 写曝光事实表、Redis |
| `AnalysisController` | 修改 | 新增 replay 和 pipeline status 管理接口 |

### 7.3 实现顺序

| 步骤 | 功能编号 | 实现内容 | 涉及文件 / 类 |
|---|---|---|---|
| 1 | F1 | 新增 inbox 表、实体、mapper、repository，并保持入口同步事实写入开关关闭 | SQL、Repository |
| 2 | F1 | `DataServiceImpl` 写 inbox，接口响应保持不变 | `DataServiceImpl` |
| 3 | F2-F3 | 新增消费者、物化器、重试与死信状态机 | Consumer、Materializer |
| 4 | F4 | 新增按事实表重建派生数据的重放接口、replay job 状态和最近任务查询 | Service、Controller |
| 5 | F5 | 新增管道状态接口并接入前端链路组件 | Analysis API、pisces-web |

## 8. 功能测试

| 功能编号 | 测试场景 | 前置条件 | 操作步骤 | 预期结果 |
|---|---|---|---|---|
| F1 | 重复事件受理 | 同一实验、同一 `clientEventId` | 连续调用 `/data/event` 两次 | inbox 只有一条记录，接口均返回成功 |
| F1 | 曝光重复受理 | 同一实验、同一访客、同一实验组 | 连续调用 `/data/exposure` 两次 | inbox 只有一条曝光记录 |
| F2 | 正常消费事件 | inbox 存在 PENDING EVENT | 运行消费者 | 事件事实表、Redis 计数、访客集合更新 |
| F2 | MAB 分母奖励 | 主指标为 `PAY_SUCCESS / PRODUCT_VIEW` | 消费 `PRODUCT_VIEW` 后再消费同 visitor 的 `PAY_SUCCESS` | MAB 先记录失败，随后升级为成功；最终同 visitor 只保留一条成功观测 |
| F3 | 消费失败重试 | Redis 写入抛异常 | 运行消费者 | 状态进入 RETRY，`retry_count` 增加 |
| F3 | 死信 | 同一记录连续失败达到阈值 | 运行消费者 | 状态进入 DEAD |
| F4 | 实验重放 | 已有 DONE inbox | 发起 replay | 派生数据按事实重建，事件不重复 |
| F5 | 管道状态 | inbox 包含 PENDING / DEAD | 查询状态接口 | 返回各状态数量和最大积压延迟 |
