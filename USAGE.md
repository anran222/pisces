# Pisces A/B实验系统 - 使用指南

## 一、环境准备

### 1.1 启动Redis

```bash
# 使用Docker启动Redis（推荐）
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 或者使用本地安装的Redis
redis-server
```

### 1.2 启动Zookeeper

```bash
# 下载Zookeeper
wget https://archive.apache.org/dist/zookeeper/zookeeper-3.9.0/apache-zookeeper-3.9.0-bin.tar.gz
tar -xzf apache-zookeeper-3.9.0-bin.tar.gz
cd apache-zookeeper-3.9.0-bin

# 配置Zookeeper
cp conf/zoo_sample.cfg conf/zoo.cfg

# 启动Zookeeper
bin/zkServer.sh start
```

### 1.3 配置Redis连接

编辑 `pisces-service/src/main/resources/application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:  # Redis密码，如果没有密码则留空
      database: 0
```

### 1.4 配置Zookeeper连接

编辑 `pisces-service/src/main/resources/application.yml`:

```yaml
zookeeper:
  connectString: localhost:2181  # Zookeeper连接地址
  sessionTimeoutMs: 30000
  connectionTimeoutMs: 30000
  maxRetries: 3
  basePath: /pisces
```

## 二、启动系统

```bash
# 编译项目
mvn clean compile

# 启动服务
cd pisces-service
mvn spring-boot:run

# 或者在项目根目录
mvn spring-boot:run -pl pisces-service
```

服务启动后，访问地址：`http://localhost:8080/api`

## 三、API使用示例

### 3.1 创建实验

```bash
POST /api/experiments
Content-Type: application/json

{
  "name": "首页按钮颜色测试",
  "description": "测试不同按钮颜色对点击率的影响",
  "startTime": "2024-01-01T00:00:00",
  "endTime": "2024-01-31T23:59:59",
  "groups": [
    {
      "id": "A",
      "name": "对照组",
      "trafficRatio": 0.5,
      "config": {
        "buttonColor": "blue",
        "buttonText": "立即购买"
      }
    },
    {
      "id": "B",
      "name": "实验组",
      "trafficRatio": 0.5,
      "config": {
        "buttonColor": "red",
        "buttonText": "立即购买"
      }
    }
  ],
  "traffic": {
    "totalTraffic": 1.0,
    "allocation": [
      {"group": "A", "ratio": 0.5},
      {"group": "B", "ratio": 0.5}
    ],
    "strategy": "HASH",
    "hashKey": "userId"
  },
  "whitelist": ["user_001", "user_002"],
  "blacklist": []
}
```

### 3.2 启动实验

```bash
POST /api/experiments/{experimentId}/start
```

### 3.3 分配用户到实验组

```bash
POST /api/traffic/assign?experimentId=exp_12345678&userId=user_001
```

响应：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": "A"
}
```

### 3.4 查询用户所在组

```bash
GET /api/traffic/user/{userId}/group?experimentId=exp_12345678
```

### 3.5 上报事件

```bash
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_12345678",
  "userId": "user_001",
  "eventType": "CLICK",
  "eventName": "button_click",
  "properties": {
    "buttonId": "btn_001",
    "page": "homepage"
  }
}
```

### 3.6 获取实验统计

```bash
GET /api/analysis/experiment/{experimentId}/statistics
```

响应示例：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "experimentId": "exp_12345678",
    "groupStatistics": {
      "A": {
        "groupId": "A",
        "userCount": 1000,
        "eventCounts": {
          "VIEW": 5000,
          "CLICK": 500,
          "CONVERT": 50
        },
        "conversionRate": 0.01
      },
      "B": {
        "groupId": "B",
        "userCount": 1000,
        "eventCounts": {
          "VIEW": 4800,
          "CLICK": 600,
          "CONVERT": 72
        },
        "conversionRate": 0.015
      }
    }
  }
}
```

### 3.7 对比实验组

```bash
GET /api/analysis/experiment/{experimentId}/compare
```

## 四、Zookeeper节点结构

实验配置存储在Zookeeper中的结构：

```
/pisces/experiments/{experimentId}
```

节点数据为JSON格式的 `ExperimentMetadata` 对象，包含：
- 实验基本信息
- 实验组配置
- 流量配置
- 白名单/黑名单

## 五、流量分配策略

### 5.1 HASH策略（推荐）
- 基于用户ID的一致性哈希
- 保证同一用户始终分配到同一组
- 适合长期实验

### 5.2 RANDOM策略
- 随机分配
- 每次分配可能不同
- 适合短期测试

### 5.3 RULE策略
- 基于业务规则分配
- 需要自定义实现

## 六、事件类型

- **VIEW**: 页面浏览
- **CLICK**: 按钮点击
- **CONVERT**: 转化事件（下单、支付等）

## 七、实验状态

- **DRAFT**: 草稿（未启动）
- **RUNNING**: 运行中
- **PAUSED**: 已暂停
- **STOPPED**: 已停止

## 八、注意事项

1. **Zookeeper连接**: 确保Zookeeper服务正常运行
2. **时间范围**: 实验只在配置的时间范围内生效
3. **流量分配**: 用户分配后会被缓存，确保一致性
4. **配置变更**: 配置变更会通过Zookeeper Watcher实时通知
5. **数据存储**: 当前版本使用内存存储，生产环境建议使用数据库

## 九、扩展建议

1. **数据持久化**: 将事件数据存储到数据库（MySQL/ClickHouse）
2. **消息队列**: 使用Kafka处理高并发事件上报
3. **统计分析**: 集成统计库进行显著性检验
4. **监控告警**: 添加Prometheus监控和告警
5. **权限管理**: 实现RBAC权限控制
6. **多租户**: 支持多租户隔离

