# 实验数据生成工具使用指南

## 概述

实验数据生成工具可以一键生成完整的A/B测试实验流程数据，包括：
- ✅ 自动创建实验（4个实验组：A、B、C、D）
- ✅ 自动启动实验
- ✅ 自动分配访客到实验组
- ✅ 自动生成事件数据（VIEW、CLICK、CONVERT）
- ✅ 模拟真实的转化率和价格数据

## 使用方式

### 方式1：通过REST API调用（推荐）

#### 1.1 快速生成实验数据（推荐参数）

使用推荐参数快速生成实验数据：
- 实验名称：`二手手机交易价格提升实验`
- 每个实验组访客数：200
- 实验开始时间：14天前

```bash
POST /api/experiments/generator/generate/quick
```

**响应**：
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

#### 1.2 快速生成默认实验数据

使用默认参数快速生成实验数据：
- 实验名称：`二手手机交易价格提升实验（自动生成）`
- 每个实验组访客数：100
- 实验开始时间：7天前

```bash
POST /api/experiments/generator/generate/default
```

**响应**：
```json
{
  "code": 200,
  "message": "默认实验数据生成成功",
  "data": {
    "experimentId": "exp_abc12345",
    "experimentName": "二手手机交易价格提升实验（自动生成）",
    "visitorCount": 100,
    "totalVisitors": 400,
    "message": "实验数据生成成功！可以调用 /api/analysis/experiment/exp_abc12345/statistics 查看统计数据"
  }
}
```

#### 1.3 自定义参数生成实验数据

```bash
POST /api/experiments/generator/generate
Content-Type: application/json

{
  "experimentName": "我的自定义实验",
  "visitorCount": 200,    // 每个实验组的访客数
  "daysAgo": 14           // 实验开始时间（几天前）
}
```

**参数说明**：
- `experimentName`（可选）：实验名称，默认值为"二手手机交易价格提升实验（自动生成）"
- `visitorCount`（可选）：每个实验组的访客数，默认值为100
- `daysAgo`（可选）：实验开始时间（几天前），默认值为7

**响应**：
```json
{
  "code": 200,
  "message": "实验数据生成成功",
  "data": {
    "experimentId": "exp_xyz67890",
    "experimentName": "我的自定义实验",
    "visitorCount": 200,
    "totalVisitors": 800,
    "message": "实验数据生成成功！可以调用 /api/analysis/experiment/exp_xyz67890/statistics 查看统计数据"
  }
}
```

### 方式2：在Java代码中直接调用

```java
@Autowired
private ExperimentDataGeneratorService generatorService;

// 方式1：使用默认参数
String experimentId = generatorService.generateDefaultExperimentData();

// 方式2：自定义参数
String experimentId = generatorService.generateCompleteExperimentData(
    "我的实验",  // 实验名称
    150,         // 每个实验组的访客数
    10           // 实验开始时间（10天前）
);
```

## 生成的数据说明

### 实验组配置

工具会自动创建4个实验组：

| 组ID | 组名 | 配置特点 | 预期转化率 | 预期平均价格 |
|------|------|---------|-----------|-------------|
| A | 基准组-当前版本 | 基础配置 | 10% | 4500元（75%市场价） |
| B | 变体1-突出信任要素 | 显示质检报告 | 11% | 4650元（77.5%市场价） |
| C | 变体2-价格锚定 | 显示市场价 | 10.5% | 4725元（78.75%市场价） |
| D | 变体3-组合策略 | 显示市场价+质检报告 | 12% | 4800元（80%市场价） |

### 生成的事件数据

对于每个访客，工具会生成：

1. **VIEW事件**（100%访客）
   - 所有访客都会浏览商品详情页
   - 包含商品ID、价格、市场价等信息

2. **CLICK事件**（约50%访客）
   - 点击率约为转化率的5倍
   - 表示访客咨询卖家或加入购物车

3. **CONVERT事件**（根据转化率）
   - A组：约10%的访客会转化
   - B组：约11%的访客会转化
   - C组：约10.5%的访客会转化
   - D组：约12%的访客会转化
   - 包含实际成交价格（核心指标）

### 数据特点

- **真实模拟**：转化率和价格数据符合实际业务场景
- **随机波动**：价格在基准值基础上添加随机波动（±150元）
- **时间分布**：事件时间分布在实验开始后的7天内
- **流量分配**：使用Thompson Sampling算法自动分配流量

## 使用示例

### 完整示例：生成数据并查看统计

```bash
# 1. 生成实验数据
curl -X POST http://localhost:9990/api/experiments/generator/generate/default

# 响应中的experimentId: exp_abc12345

# 2. 查看实验统计
curl http://localhost:9990/api/analysis/experiment/exp_abc12345/statistics

# 3. 查看贝叶斯分析
curl http://localhost:9990/api/analysis/experiment/exp_abc12345/bayesian

# 4. 查看因果推断分析
curl http://localhost:9990/api/analysis/experiment/exp_abc12345/causal

# 5. 查看HTE分析
curl http://localhost:9990/api/analysis/experiment/exp_abc12345/hte
```

### JavaScript示例

```javascript
// 生成实验数据
async function generateExperimentData() {
  const response = await fetch('http://localhost:9990/api/experiments/generator/generate/default', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    }
  });
  
  const result = await response.json();
  console.log('实验ID:', result.data.experimentId);
  console.log('总访客数:', result.data.totalVisitors);
  
  // 查看统计数据
  const statsResponse = await fetch(
    `http://localhost:9990/api/analysis/experiment/${result.data.experimentId}/statistics`
  );
  const stats = await statsResponse.json();
  console.log('统计数据:', stats);
}

generateExperimentData();
```

## 注意事项

1. **数据量**：生成的访客数量 = `visitorCount × 4`（4个实验组）
2. **性能**：生成大量数据可能需要一些时间，建议：
   - 测试环境：每个组50-100个访客
   - 演示环境：每个组100-200个访客
   - 生产环境：不建议使用此工具
3. **数据覆盖**：生成的数据会覆盖内存中的实验数据，如果实验ID已存在，会创建新的实验
4. **时间范围**：事件时间分布在实验开始后的7天内，确保数据的时间分布合理

## 常见问题

### Q: 生成的实验数据在哪里？
A: 所有数据存储在内存中（ConcurrentHashMap），如果配置了Redis，部分数据会缓存到Redis。

### Q: 如何清除生成的数据？
A: 调用删除实验接口：`DELETE /api/experiments/{experimentId}`

### Q: 可以生成多个实验吗？
A: 可以，每次调用都会生成一个新的实验，实验ID是唯一的。

### Q: 生成的数据会持久化吗？
A: 不会，数据存储在内存中，应用重启后会丢失。如需持久化，需要集成消息队列或数据库。

## 技术实现

- **服务类**：`ExperimentDataGeneratorService`
- **控制器**：`ExperimentDataGeneratorController`
- **API路径**：`/api/experiments/generator/*`

## 相关文档

- [完整实施指南](COMPLETE_GUIDE.md)
- [API文档](README.md)
