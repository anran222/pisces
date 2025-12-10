# Pisces A/B实验系统 - 功能需求与架构设计

## 一、系统概述

Pisces是一个基于Zookeeper的A/B实验系统，用于支持多组实验配置管理、流量分配、数据收集和效果分析。

## 二、核心功能需求

### 2.1 实验管理模块

#### 2.1.1 实验生命周期管理
- **创建实验**
  - 实验名称、描述
  - 实验类型（A/B测试、多变量测试等）
  - 实验时间范围（开始时间、结束时间）
  - 实验状态（草稿、运行中、已暂停、已结束）

- **实验配置**
  - 实验组配置（A组、B组、C组等）
  - 流量分配比例（如：A组50%，B组50%）
  - 实验目标指标（转化率、点击率、GMV等）
  - 实验白名单/黑名单用户

- **实验操作**
  - 启动实验
  - 暂停实验
  - 停止实验
  - 删除实验
  - 实验配置修改（需要版本控制）

#### 2.1.2 实验版本管理
- 配置版本历史
- 配置回滚
- 配置变更记录

### 2.2 流量分配模块

#### 2.2.1 用户分组策略
- **随机分配**
  - 基于用户ID的哈希分配
  - 保证同一用户始终分配到同一组

- **一致性哈希**
  - 支持用户在不同实验中的一致性分配
  - 避免用户在不同实验中频繁切换组

- **自定义规则**
  - 基于用户属性（地区、年龄、VIP等级等）
  - 基于业务规则的分组

#### 2.2.2 流量控制
- 流量百分比控制（如：只对10%的用户开启实验）
- 流量逐步放量（灰度发布）
- 流量互斥（确保用户不会同时参与多个冲突实验）

### 2.3 配置管理模块

#### 2.3.1 实验配置存储（Zookeeper）
- **配置结构**
  ```
  /pisces/experiments/{experimentId}
    ├── metadata (实验元数据：名称、状态、时间等)
    ├── groups (实验组配置)
    │   ├── A (A组配置)
    │   ├── B (B组配置)
    │   └── ...
    ├── traffic (流量分配配置)
    └── whitelist (白名单)
  ```

- **配置特性**
  - 实时配置更新
  - 配置变更通知（Watcher机制）
  - 配置版本管理
  - 配置一致性保证

#### 2.3.2 配置下发
- 配置推送到客户端SDK
- 配置缓存机制
- 配置降级策略（Zookeeper不可用时）

### 2.4 数据收集模块

#### 2.4.1 事件追踪
- **用户行为事件**
  - 页面浏览
  - 按钮点击
  - 转化事件（下单、支付等）
  - 自定义事件

- **实验事件**
  - 用户进入实验
  - 用户分配到实验组
  - 实验配置变更事件

#### 2.4.2 数据上报
- 实时数据上报
- 批量数据上报
- 数据去重
- 数据校验

### 2.5 数据分析模块

#### 2.5.1 实时统计
- 实验组用户数统计
- 事件触发次数统计
- 实时转化率计算

#### 2.5.2 数据分析
- 实验效果对比（A组 vs B组）
- 统计显著性检验
- 置信区间计算
- 多维度数据分析（时间、地区、用户属性等）

#### 2.5.3 数据看板
- 实验概览
- 实时数据展示
- 历史趋势分析
- 数据导出

### 2.6 用户管理模块

#### 2.6.1 用户标识
- 用户ID管理
- 设备ID管理
- 匿名用户处理

#### 2.6.2 用户分组
- 用户实验组查询
- 用户参与实验历史
- 用户白名单/黑名单管理

### 2.7 权限管理模块

#### 2.7.1 角色权限
- 管理员：所有权限
- 实验创建者：创建和管理自己的实验
- 查看者：只能查看实验数据

#### 2.7.2 操作审计
- 操作日志记录
- 配置变更审计

## 三、Zookeeper使用场景

### 3.1 配置中心
- **存储实验配置**
  - 实验元数据
  - 实验组配置
  - 流量分配规则

- **配置变更通知**
  - 使用Watcher监听配置变化
  - 实时推送配置更新到客户端

### 3.2 分布式协调
- **分布式锁**
  - 实验启动/停止的原子性操作
  - 配置更新的并发控制

- **服务发现**
  - 如果有多个服务实例，提供服务注册与发现

### 3.3 数据一致性
- **配置一致性**
  - 确保所有节点配置一致
  - 配置变更的原子性

## 四、系统架构设计

### 4.1 模块划分

```
pisces/
├── pisces-common/          # 公共模块
│   ├── request/           # 请求DTO
│   ├── response/          # 响应DTO
│   └── model/             # 实体模型
│
├── pisces-service/        # 服务层
│   ├── experiment/        # 实验管理服务
│   ├── traffic/           # 流量分配服务
│   ├── config/            # 配置管理服务（Zookeeper）
│   ├── data/              # 数据收集服务
│   ├── analysis/          # 数据分析服务
│   └── zookeeper/         # Zookeeper客户端封装
│
└── pisces-api/            # API层
    ├── experiment/         # 实验管理API
    ├── traffic/            # 流量分配API
    ├── config/             # 配置查询API
    ├── data/               # 数据上报API
    └── analysis/           # 数据分析API
```

### 4.2 核心服务设计

#### 4.2.1 实验管理服务（ExperimentService）
- `createExperiment()` - 创建实验
- `updateExperiment()` - 更新实验
- `startExperiment()` - 启动实验
- `stopExperiment()` - 停止实验
- `getExperiment()` - 查询实验
- `listExperiments()` - 实验列表

#### 4.2.2 流量分配服务（TrafficService）
- `assignGroup()` - 分配用户到实验组
- `getUserGroup()` - 查询用户所在组
- `isInExperiment()` - 判断用户是否在实验中

#### 4.2.3 配置管理服务（ConfigService）
- `saveConfig()` - 保存配置到Zookeeper
- `getConfig()` - 从Zookeeper获取配置
- `watchConfig()` - 监听配置变化
- `deleteConfig()` - 删除配置

#### 4.2.4 数据收集服务（DataService）
- `reportEvent()` - 上报事件
- `batchReport()` - 批量上报
- `queryEvents()` - 查询事件

#### 4.2.5 数据分析服务（AnalysisService）
- `getStatistics()` - 获取统计数据
- `compareGroups()` - 对比实验组
- `calculateMetrics()` - 计算指标

### 4.3 Zookeeper节点设计

```
/pisces
├── experiments/                    # 实验根节点
│   ├── {experimentId}/            # 单个实验
│   │   ├── metadata               # 元数据（JSON）
│   │   ├── groups/                # 实验组
│   │   │   ├── A                  # A组配置（JSON）
│   │   │   ├── B                  # B组配置（JSON）
│   │   │   └── ...
│   │   ├── traffic                # 流量配置（JSON）
│   │   ├── whitelist              # 白名单（JSON数组）
│   │   └── blacklist              # 黑名单（JSON数组）
│   └── ...
├── users/                         # 用户分组信息（可选，用于缓存）
│   └── {userId}/
│       └── experiments            # 用户参与的实验（JSON）
└── locks/                         # 分布式锁
    └── experiment-{experimentId}  # 实验操作锁
```

## 五、数据模型设计

### 5.1 实验（Experiment）
```java
{
  "id": "exp_001",
  "name": "首页按钮颜色测试",
  "description": "测试不同按钮颜色对点击率的影响",
  "status": "RUNNING", // DRAFT, RUNNING, PAUSED, STOPPED
  "startTime": "2024-01-01 00:00:00",
  "endTime": "2024-01-31 23:59:59",
  "creator": "admin",
  "createTime": "2023-12-25 10:00:00",
  "updateTime": "2024-01-01 00:00:00"
}
```

### 5.2 实验组（ExperimentGroup）
```java
{
  "id": "A",
  "name": "对照组",
  "trafficRatio": 0.5,  // 50%流量
  "config": {
    "buttonColor": "blue",
    "buttonText": "立即购买"
  }
}
```

### 5.3 流量配置（TrafficConfig）
```java
{
  "totalTraffic": 1.0,  // 100%流量
  "allocation": [
    {"group": "A", "ratio": 0.5},
    {"group": "B", "ratio": 0.5}
  ],
  "strategy": "HASH",  // RANDOM, HASH, RULE
  "hashKey": "userId"
}
```

### 5.4 事件（Event）
```java
{
  "eventId": "evt_001",
  "experimentId": "exp_001",
  "userId": "user_123",
  "groupId": "A",
  "eventType": "CLICK",  // VIEW, CLICK, CONVERT
  "eventName": "button_click",
  "properties": {
    "buttonId": "btn_001",
    "page": "homepage"
  },
  "timestamp": "2024-01-01 12:00:00"
}
```

## 六、API设计

### 6.1 实验管理API
- `POST /api/experiments` - 创建实验
- `PUT /api/experiments/{id}` - 更新实验
- `GET /api/experiments/{id}` - 查询实验
- `GET /api/experiments` - 实验列表
- `POST /api/experiments/{id}/start` - 启动实验
- `POST /api/experiments/{id}/stop` - 停止实验
- `POST /api/experiments/{id}/pause` - 暂停实验

### 6.2 流量分配API
- `POST /api/traffic/assign` - 分配用户到实验组
- `GET /api/traffic/user/{userId}/group` - 查询用户所在组
- `GET /api/traffic/user/{userId}/experiments` - 查询用户参与的实验

### 6.3 配置查询API
- `GET /api/config/experiment/{id}` - 获取实验配置
- `GET /api/config/user/{userId}` - 获取用户配置

### 6.4 数据上报API
- `POST /api/data/event` - 上报事件
- `POST /api/data/events` - 批量上报事件

### 6.5 数据分析API
- `GET /api/analysis/experiment/{id}/statistics` - 获取实验统计
- `GET /api/analysis/experiment/{id}/compare` - 实验组对比
- `GET /api/analysis/experiment/{id}/metrics` - 获取指标数据

## 七、技术选型

### 7.1 核心依赖
- **Zookeeper Client**: Curator（Apache Curator）
- **数据存储**: MySQL（实验元数据）+ Redis（缓存）
- **消息队列**: Kafka（可选，用于数据上报）
- **统计分析**: 自研或集成第三方统计库

### 7.2 关键技术点
- Zookeeper Watcher机制实现配置实时推送
- 一致性哈希算法实现用户分组
- 分布式锁保证操作原子性
- 配置缓存减少Zookeeper压力

## 八、非功能性需求

### 8.1 性能要求
- 流量分配响应时间 < 10ms
- 配置查询响应时间 < 50ms
- 支持10万+ QPS

### 8.2 可用性要求
- 系统可用性 > 99.9%
- Zookeeper故障时的降级策略
- 配置本地缓存机制

### 8.3 扩展性要求
- 支持1000+并发实验
- 支持百万级用户
- 支持多租户

## 九、实施计划

### Phase 1: 基础功能
1. Zookeeper集成
2. 实验CRUD
3. 基础流量分配

### Phase 2: 核心功能
1. 配置管理（Zookeeper）
2. 数据收集
3. 基础统计

### Phase 3: 高级功能
1. 数据分析
2. 数据看板
3. 权限管理

### Phase 4: 优化
1. 性能优化
2. 高可用
3. 监控告警

