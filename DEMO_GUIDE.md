# Pisces A/B测试系统 - 演示指南

## ⚠️ 重要提示：URL路径

**注意**：根据 `application.yml` 配置，服务的 `context-path` 是 `/api`，所以所有API请求都需要加上 `/api` 前缀。

**正确的URL格式**：
```
http://localhost:9990/api/experiments/generator/generate/quick
```

**错误的URL格式**（会返回404）：
```
http://localhost:9990/experiments/generator/generate/quick  ❌
```

---

## 快速开始（推荐）

### 方式一：一键生成完整实验数据（最简单）

这是最快的演示方式，一键生成完整的实验流程数据。

#### 1. 启动服务

确保Redis和Zookeeper已启动：

```bash
# 启动Redis（如果未启动）
redis-server

# 启动Zookeeper（如果未启动）
zkServer start  # macOS
# 或
zkServer.sh start  # Linux
```

#### 2. 启动Pisces服务

```bash
cd /Users/a147735/IdeaProjects/personal/pisces
mvn clean install
mvn spring-boot:run -pl pisces-service
```

服务启动后，访问地址：`http://localhost:9990/api`

#### 3. 生成实验数据

**方式A：快速生成（推荐参数）**

```bash
curl -X POST http://localhost:9990/api/experiments/generator/generate/quick
```

**方式B：使用默认参数**

```bash
curl -X POST http://localhost:9990/api/experiments/generator/generate/default
```

**方式C：自定义参数**

```bash
curl -X POST http://localhost:9990/api/experiments/generator/generate \
  -H "Content-Type: application/json" \
  -d '{
    "experimentName": "我的演示实验",
    "visitorCount": 150,
    "daysAgo": 10
  }'
```

**响应示例**：
```json
{
  "code": 200,
  "message": "实验数据生成成功",
  "data": {
    "experimentId": "exp_abc12345",
    "experimentName": "二手手机交易价格提升实验",
    "visitorCount": 200,
    "totalVisitors": 800,
    "daysAgo": 14,
    "experimentDuration": "14天",
    "message": "实验数据生成成功！可以调用 /api/analysis/experiment/exp_abc12345/statistics 查看统计数据"
  }
}
```

**记住返回的 `experimentId`**，后续步骤会用到。

#### 4. 查看实验结果

##### 4.1 查看基础统计

```bash
# 替换 {experimentId} 为实际返回的实验ID
curl http://localhost:9990/api/analysis/experiment/{experimentId}/statistics
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "experimentId": "exp_abc12345",
    "experimentName": "二手手机交易价格提升实验",
    "totalVisitors": 800,
    "groupStatistics": {
      "A": {
        "groupId": "A",
        "groupName": "基准组-当前版本",
        "userCount": 200,
        "viewCount": 200,
        "clickCount": 100,
        "convertCount": 20,
        "conversionRate": 0.10,
        "averagePrice": 4500.0
      },
      "B": {
        "groupId": "B",
        "groupName": "变体1-突出信任要素",
        "userCount": 200,
        "viewCount": 200,
        "clickCount": 110,
        "convertCount": 22,
        "conversionRate": 0.11,
        "averagePrice": 4650.0
      },
      "C": {
        "groupId": "C",
        "groupName": "变体2-价格锚定",
        "userCount": 200,
        "viewCount": 200,
        "clickCount": 105,
        "convertCount": 21,
        "conversionRate": 0.105,
        "averagePrice": 4725.0
      },
      "D": {
        "groupId": "D",
        "groupName": "变体3-组合策略",
        "userCount": 200,
        "viewCount": 200,
        "clickCount": 120,
        "convertCount": 24,
        "conversionRate": 0.12,
        "averagePrice": 4800.0
      }
    }
  }
}
```

##### 4.2 查看贝叶斯分析（AI赋能）

```bash
curl http://localhost:9990/api/analysis/experiment/{experimentId}/bayesian
```

**响应示例**：
```json
{
  "code": 200,
  "data": {
    "experimentId": "exp_abc12345",
    "winRates": {
      "A": 0.15,
      "B": 0.20,
      "C": "0.25",
      "D": 0.40
    },
    "recommendations": [
      "变体D（组合策略）有40%的概率是最优方案",
      "建议继续运行实验以收集更多数据"
    ]
  }
}
```

##### 4.3 查看因果推断分析

```bash
curl http://localhost:9990/api/analysis/experiment/{experimentId}/causal
```

##### 4.4 查看HTE分析（异质性处理效应）

```bash
curl http://localhost:9990/api/analysis/experiment/{experimentId}/hte
```

##### 4.5 查看组对比

```bash
curl http://localhost:9990/api/analysis/experiment/{experimentId}/compare
```

#### 5. 查看MAB算法参数（AI赋能）

```bash
# 查看Thompson Sampling的Beta参数
curl "http://localhost:9990/api/traffic/experiment/{experimentId}/mab/beta?groupId=D"
```

---

## 方式二：手动创建实验（完整流程）

如果你想了解完整的实验流程，可以手动创建实验。

### 步骤1：创建实验

```bash
curl -X POST http://localhost:9990/api/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "二手手机交易价格提升实验",
    "description": "通过优化商品展示方式提升交易价格",
    "startTime": "2024-01-01T00:00:00",
    "endTime": "2024-01-31T23:59:59",
    "groups": [
      {
        "groupId": "A",
        "groupName": "基准组-当前版本",
        "config": {"displayMode": "basic"}
      },
      {
        "groupId": "B",
        "groupName": "变体1-突出信任要素",
        "config": {"displayMode": "trust", "showInspection": true}
      },
      {
        "groupId": "C",
        "groupName": "变体2-价格锚定",
        "config": {"displayMode": "price", "showMarketPrice": true}
      },
      {
        "groupId": "D",
        "groupName": "变体3-组合策略",
        "config": {"displayMode": "combined", "showInspection": true, "showMarketPrice": true}
      }
    ],
    "traffic": {
      "totalTraffic": 1.0,
      "strategy": "MAB_THOMPSON_SAMPLING",
      "allocations": [
        {"group": "A", "ratio": 0.25},
        {"group": "B", "ratio": 0.25},
        {"group": "C", "ratio": 0.25},
        {"group": "D", "ratio": 0.25}
      ]
    }
  }'
```

**响应**：
```json
{
  "code": 200,
  "message": "实验创建成功",
  "data": {
    "experimentId": "exp_xyz67890",
    "name": "二手手机交易价格提升实验",
    "status": "DRAFT"
  }
}
```

### 步骤2：启动实验

```bash
curl -X POST http://localhost:9990/api/experiments/{experimentId}/start
```

### 步骤3：分配访客到实验组

```bash
curl -X POST http://localhost:9990/api/traffic/assign \
  -H "Content-Type: application/json" \
  -d '{
    "experimentId": "{experimentId}",
    "visitorId": "visitor_001"
  }'
```

**响应**：
```json
{
  "code": 200,
  "data": "D"
}
```

### 步骤4：上报事件

#### 4.1 上报浏览事件（VIEW）

```bash
curl -X POST http://localhost:9990/api/data/event \
  -H "Content-Type: application/json" \
  -d '{
    "experimentId": "{experimentId}",
    "visitorId": "visitor_001",
    "eventType": "VIEW",
    "eventName": "商品详情页浏览",
    "properties": {
      "productId": "product_123",
      "productName": "iPhone 13 Pro",
      "marketPrice": 6000,
      "listPrice": 4500
    }
  }'
```

#### 4.2 上报点击事件（CLICK）

```bash
curl -X POST http://localhost:9990/api/data/event \
  -H "Content-Type: application/json" \
  -d '{
    "experimentId": "{experimentId}",
    "visitorId": "visitor_001",
    "eventType": "CLICK",
    "eventName": "咨询卖家",
    "properties": {
      "productId": "product_123",
      "action": "contact_seller"
    }
  }'
```

#### 4.3 上报转化事件（CONVERT）

```bash
curl -X POST http://localhost:9990/api/data/event \
  -H "Content-Type: application/json" \
  -d '{
    "experimentId": "{experimentId}",
    "visitorId": "visitor_001",
    "eventType": "CONVERT",
    "eventName": "交易完成",
    "properties": {
      "productId": "product_123",
      "transactionPrice": 4800,
      "transactionTime": "2024-01-15T10:30:00"
    }
  }'
```

### 步骤5：查看统计结果

同方式一的步骤4。

---

## 使用Postman或浏览器演示

### Postman集合

你可以导入以下Postman请求：

#### 1. 生成实验数据
```
POST http://localhost:9990/api/experiments/generator/generate/quick
```

#### 2. 查看统计
```
GET http://localhost:9990/api/analysis/experiment/{experimentId}/statistics
```

#### 3. 查看贝叶斯分析
```
GET http://localhost:9990/api/analysis/experiment/{experimentId}/bayesian
```

#### 4. 查看因果推断
```
GET http://localhost:9990/api/analysis/experiment/{experimentId}/causal
```

#### 5. 查看HTE分析
```
GET http://localhost:9990/api/analysis/experiment/{experimentId}/hte
```

### 浏览器演示

如果使用浏览器，可以直接访问：

```
http://localhost:9990/api/analysis/experiment/{experimentId}/statistics
```

注意：需要先通过API生成实验数据获取 `experimentId`。

---

## 演示要点

### 1. 核心功能展示

- ✅ **实验管理**：创建、启动、停止实验
- ✅ **流量分配**：自动分配访客到实验组（支持多种策略）
- ✅ **数据收集**：事件上报（VIEW、CLICK、CONVERT）
- ✅ **统计分析**：基础统计、组对比
- ✅ **AI赋能**：贝叶斯分析、因果推断、HTE分析、MAB算法

### 2. AI赋能特性

- **Multi-Armed Bandit (MAB)**：Thompson Sampling和UCB算法，动态调整流量分配
- **贝叶斯统计**：实时计算胜率，支持早期停止
- **因果推断**：DID、PSM、Causal Forest，无偏效应估计
- **HTE分析**：识别敏感用户群体

### 3. 数据存储

- **Zookeeper**：存储实验配置（持久化）
- **Redis**：存储运行时数据（高性能、自动过期）

---

## 常见问题

### Q1: 服务启动失败，提示Redis连接失败

**A**: 确保Redis已启动：
```bash
redis-cli ping
# 应该返回 PONG
```

如果Redis未启动：
```bash
redis-server
```

### Q2: 服务启动失败，提示Zookeeper连接失败

**A**: 确保Zookeeper已启动：
```bash
# macOS
zkServer status

# Linux
zkServer.sh status
```

如果Zookeeper未启动：
```bash
# macOS
zkServer start

# Linux
zkServer.sh start
```

### Q3: 生成的实验ID每次都不同吗？

**A**: 是的，每次调用生成工具都会创建新的实验，实验ID使用UUID生成，确保唯一性。

### Q4: 如何清除实验数据？

**A**: 删除实验：
```bash
curl -X DELETE http://localhost:9990/api/experiments/{experimentId}
```

### Q5: 可以同时运行多个实验吗？

**A**: 可以，每次生成都会创建新的实验，互不影响。

### Q6: 数据会持久化吗？

**A**: 
- 实验配置存储在Zookeeper中，会持久化
- 运行时数据（事件、统计）存储在Redis中，默认90天过期

### Q7: 为什么返回404错误？

**A**: 请检查URL路径是否正确。根据配置，所有API都需要加上 `/api` 前缀：
- ✅ 正确：`http://localhost:9990/api/experiments/generator/generate/quick`
- ❌ 错误：`http://localhost:9990/experiments/generator/generate/quick`

### Q8: 为什么保存实验配置失败，提示LocalDateTime序列化错误？

**A**: 这个问题已经修复。如果仍然出现，请确保：
1. 代码已更新到最新版本
2. 服务已重新编译和重启
3. 确保 `ZookeeperClient` 已配置 `JavaTimeModule` 支持

---

## 演示脚本

创建一个演示脚本 `demo.sh`：

```bash
#!/bin/bash

BASE_URL="http://localhost:9990/api"

echo "=== Pisces A/B测试系统演示 ==="
echo ""

# 1. 生成实验数据
echo "1. 生成实验数据..."
RESPONSE=$(curl -s -X POST ${BASE_URL}/experiments/generator/generate/quick)
EXPERIMENT_ID=$(echo $RESPONSE | grep -o '"experimentId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$EXPERIMENT_ID" ]; then
    echo "❌ 生成实验数据失败"
    echo "响应: $RESPONSE"
    exit 1
fi

echo "✅ 实验ID: $EXPERIMENT_ID"
echo ""

# 2. 查看统计
echo "2. 查看实验统计..."
curl -s ${BASE_URL}/analysis/experiment/${EXPERIMENT_ID}/statistics | python3 -m json.tool
echo ""

# 3. 查看贝叶斯分析
echo "3. 查看贝叶斯分析..."
curl -s ${BASE_URL}/analysis/experiment/${EXPERIMENT_ID}/bayesian | python3 -m json.tool
echo ""

# 4. 查看因果推断
echo "4. 查看因果推断分析..."
curl -s ${BASE_URL}/analysis/experiment/${EXPERIMENT_ID}/causal | python3 -m json.tool
echo ""

# 5. 查看HTE分析
echo "5. 查看HTE分析..."
curl -s ${BASE_URL}/analysis/experiment/${EXPERIMENT_ID}/hte | python3 -m json.tool
echo ""

echo "=== 演示完成 ==="
echo "实验ID: $EXPERIMENT_ID"
echo "可以继续使用以下命令查看详细数据："
echo "  curl ${BASE_URL}/analysis/experiment/${EXPERIMENT_ID}/statistics"
```

使用方法：
```bash
chmod +x demo.sh
./demo.sh
```

---

## 下一步

- 查看[完整实施指南](COMPLETE_GUIDE.md)了解详细集成方式
- 查看[实验数据生成工具](EXPERIMENT_DATA_GENERATOR.md)了解数据生成详情
- 查看[存储迁移文档](STORAGE_MIGRATION.md)了解数据存储架构
