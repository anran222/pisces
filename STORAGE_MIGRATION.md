# 数据存储迁移文档

## 概述

本次迁移将所有实验数据从内存存储（ConcurrentHashMap）迁移到分布式存储（Zookeeper + Redis），实现数据的持久化和分布式访问。

## 存储架构

### Zookeeper存储
- **用途**：存储实验配置（ExperimentMetadata）
- **路径**：`/pisces/experiments/{experimentId}`
- **特点**：持久化、支持配置变更监听、分布式一致性

### Redis存储
- **用途**：存储运行时数据（事件、计数器、缓存等）
- **特点**：高性能、支持过期时间、分布式缓存

## 数据迁移详情

### 1. ExperimentServiceImpl

#### 迁移前
- `experimentCache` (ConcurrentHashMap) - 内存存储实验基本信息

#### 迁移后
- **实验配置**：存储在Zookeeper（通过ConfigService）
- **实验列表**：从Zookeeper获取所有实验ID，然后逐个获取配置

#### 修改内容
- 移除 `experimentCache` 字段
- `listExperiments()` 方法改为从Zookeeper获取
- 所有实验操作直接操作Zookeeper，不再维护内存缓存

### 2. DataServiceImpl

#### 迁移前
- `eventStore` (ConcurrentHashMap<String, List<Event>>) - 事件存储
- `eventCounters` (ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>) - 事件计数器
- `visitorSets` (ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>) - 访客集合

#### 迁移后
- **事件存储**：Redis List
  - Key: `pisces:event:store:{experimentId}:{groupId}`
  - 过期时间：90天
- **事件计数器**：Redis Hash
  - Key: `pisces:event:counter:{experimentId}:{groupId}`
  - Field: `{eventType}`
  - Value: 计数
  - 过期时间：90天
- **访客集合**：Redis Set
  - Key: `pisces:visitor:set:{experimentId}:{groupId}`
  - 自动去重
  - 过期时间：90天

#### 修改内容
- 注入 `RedisTemplate<String, Object>`
- 所有数据操作改为Redis操作
- 使用Redis的数据结构特性（List、Hash、Set）

### 3. TrafficServiceImpl

#### 迁移前
- `userGroupCache` (ConcurrentHashMap<String, Map<String, String>>) - 访客分组缓存

#### 迁移后
- **访客分组缓存**：Redis Hash
  - Key: `pisces:traffic:group:{visitorId}`
  - Field: `{experimentId}`
  - Value: `{groupId}`
  - 过期时间：30天

#### 修改内容
- 注入 `RedisTemplate<String, Object>`
- `assignGroup()` 和 `getUserGroup()` 从Redis读取
- `cacheUserGroup()` 写入Redis
- `getUserExperiments()` 从Redis Hash获取所有字段

### 4. MultiArmedBanditServiceImpl

#### 迁移前
- `betaParamsCache` (ConcurrentHashMap<String, Map<String, BetaParams>>) - Beta分布参数
- `ucbStatsCache` (ConcurrentHashMap<String, Map<String, UCBStats>>) - UCB统计信息
- `totalTrialsCache` (ConcurrentHashMap<String, AtomicLong>) - 总实验次数

#### 迁移后
- **Beta分布参数**：Redis Hash
  - Key: `pisces:mab:beta:{experimentId}`
  - Field: `{groupId}`
  - Value: `{alpha, beta}` (Map)
  - 过期时间：90天
- **UCB统计信息**：Redis Hash
  - Key: `pisces:mab:ucb:{experimentId}`
  - Field: `{groupId}`
  - Value: `{trials, successes, averageReward}` (Map)
  - 过期时间：90天
- **总实验次数**：Redis String
  - Key: `pisces:mab:trials:{experimentId}`
  - Value: 数字
  - 过期时间：90天

#### 修改内容
- 注入 `RedisTemplate<String, Object>`
- 将 `BetaParams` 和 `UCBStats` 改为普通类（移除Atomic类型）
- 添加 `getBetaParams()`、`saveBetaParams()`、`getUCBStats()`、`saveUCBStats()` 等方法
- 所有MAB算法数据操作改为Redis操作

### 5. ConfigServiceImpl

#### 新增功能
- `getAllExperimentIds()` - 从Zookeeper获取所有实验ID列表

#### 修改内容
- 在 `ZookeeperClient` 中添加 `getChildren()` 方法
- `getAllExperimentIds()` 使用 `zookeeperClient.getChildren()` 获取实验列表

### 6. ZookeeperClient

#### 新增方法
- `getChildren(String path)` - 获取子节点列表

## Redis Key命名规范

### 事件相关
- `pisces:event:store:{experimentId}:{groupId}` - 事件列表
- `pisces:event:counter:{experimentId}:{groupId}` - 事件计数器
- `pisces:visitor:set:{experimentId}:{groupId}` - 访客集合

### 流量分配相关
- `pisces:traffic:group:{visitorId}` - 访客分组缓存

### MAB算法相关
- `pisces:mab:beta:{experimentId}` - Beta分布参数
- `pisces:mab:ucb:{experimentId}` - UCB统计信息
- `pisces:mab:trials:{experimentId}` - 总实验次数

## 数据过期策略

- **事件数据**：90天（eventStore、eventCounters、visitorSets）
- **访客分组缓存**：30天（userGroupCache）
- **MAB算法数据**：90天（betaParams、ucbStats、totalTrials）

## 实验ID生成

实验ID使用UUID生成，确保每次生成都是唯一的：
```java
String experimentId = "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
```

格式：`exp_` + 8位随机字符（如：`exp_a1b2c3d4`）

## 兼容性说明

### 保留的内存缓存
- `ConfigServiceImpl.configCache` - 实验配置的本地缓存（提升性能）
- `ConfigServiceImpl.listeners` - 配置变更监听器（本地管理）

这些缓存用于提升性能，不影响数据持久化。

## 使用说明

### 启动前准备
1. **启动Zookeeper**：确保Zookeeper服务运行在 `localhost:2181`
2. **启动Redis**：确保Redis服务运行在 `localhost:6379`

### 配置检查
检查 `application.yml` 中的配置：
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:  # Redis密码，如果没有密码则留空
      database: 0

zookeeper:
  connectString: localhost:2181
  sessionTimeoutMs: 30000
  connectionTimeoutMs: 30000
  maxRetries: 3
  basePath: /pisces
```

### 数据查看

#### 查看Zookeeper中的实验配置
```bash
# 使用zkCli.sh连接Zookeeper
zkCli.sh -server localhost:2181

# 查看所有实验
ls /pisces/experiments

# 查看特定实验配置
get /pisces/experiments/{experimentId}
```

#### 查看Redis中的数据
```bash
# 连接Redis
redis-cli

# 查看所有Key
KEYS pisces:*

# 查看事件计数器
HGETALL pisces:event:counter:{experimentId}:{groupId}

# 查看访客集合大小
SCARD pisces:visitor:set:{experimentId}:{groupId}

# 查看MAB Beta参数
HGETALL pisces:mab:beta:{experimentId}
```

## 性能优化

### Redis优化
- 使用Redis的Hash结构存储结构化数据
- 使用Set结构自动去重访客
- 使用List结构存储事件序列
- 设置合理的过期时间，自动清理过期数据

### Zookeeper优化
- 使用本地缓存（configCache）减少Zookeeper访问
- 配置变更监听器自动更新缓存
- 实验列表按需加载

## 故障处理

### Zookeeper连接失败
- 系统会记录错误日志
- 实验配置操作会失败，抛出异常
- 建议：确保Zookeeper服务正常运行

### Redis连接失败
- 系统会记录错误日志
- 数据操作会失败，抛出异常
- 建议：确保Redis服务正常运行

### 数据迁移
如果需要从旧版本迁移数据：
1. 旧版本数据在内存中，无法直接迁移
2. 建议：重新生成实验数据或通过API重新上报

## 注意事项

1. **数据持久化**：所有数据现在存储在Zookeeper和Redis中，应用重启后数据不会丢失
2. **分布式支持**：多个应用实例可以共享同一份数据
3. **数据过期**：Redis中的数据设置了过期时间，过期后会自动清理
4. **实验ID唯一性**：使用UUID确保每次生成的实验ID都是唯一的

## 相关文件

### 修改的文件
- `ExperimentServiceImpl.java` - 移除experimentCache，从Zookeeper获取实验列表
- `DataServiceImpl.java` - 迁移到Redis存储
- `TrafficServiceImpl.java` - 迁移到Redis存储
- `MultiArmedBanditServiceImpl.java` - 迁移到Redis存储
- `ConfigServiceImpl.java` - 添加getAllExperimentIds方法
- `ZookeeperClient.java` - 添加getChildren方法

### 新增的文件
- `STORAGE_MIGRATION.md` - 本文档
