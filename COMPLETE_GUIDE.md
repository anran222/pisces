# Pisces A/B测试系统 - 完整实施指南（无用户系统版本）

## 目录

1. [项目概述](#一项目概述)
2. [实验设计：二手手机交易价格提升](#二实验设计二手手机交易价格提升)
3. [Pisces SDK使用指南](#三pisces-sdk使用指南)
4. [后端集成](#四后端集成)
5. [前端集成](#五前端集成)
6. [数据存储设计](#六数据存储设计)
7. [完整实施流程](#七完整实施流程)
8. [代码示例](#八代码示例)
9. [监控与优化](#九监控与优化)
10. [故障排查](#十故障排查)

---

## 一、项目概述

### 1.1 业务背景

您是一个二手手机交易平台，当前面临的问题是：
- **交易价格偏低**：平台上交易的手机价格仅为市场价的75%（例如：iPhone 13 Pro市场价6000元，平台成交价4500元）
- **卖家收益不高**：价格偏低导致卖家收益减少，影响平台活跃度
- **平台佣金受影响**：价格偏低导致平台佣金收入减少

### 1.2 实验目标

通过A/B测试优化商品展示方式，提升交易价格：
- **核心指标**：平均成交价格（Average Transaction Price, ATP）
- **目标提升**：将平均成交价格从市场价的75%提升至80%（提升6.7%）
- **约束条件**：成交率不能下降（确保价格提升不以牺牲成交量为代价）

### 1.3 实验假设

**核心假设**：通过优化商品详情页的展示方式，突出商品价值点和信任要素，能够提升买家的价格感知，从而提高成交价格。

**具体假设**：
1. **假设1**：在商品标题和描述中突出"官方质检"、"无拆修"等信任要素，能够提升价格
2. **假设2**：展示市场参考价格对比，能够提升买家的价格接受度
3. **假设3**：展示详细的质检报告和质保信息，能够提升价格
4. **假设4**：组合使用多种优化策略，效果更佳

### 1.4 问题分析

影响二手手机交易价格的因素可能包括：
1. **商品展示方式**：标题、图片、描述的质量
2. **信任要素**：质检报告、质保信息、卖家信用
3. **价格锚定**：参考价格、市场价对比
4. **稀缺性暗示**：库存数量、限时优惠
5. **社交证明**：历史成交记录、好评率

### 1.5 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                      前端应用层                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  商品详情页  │  │   交易页面   │  │   管理后台   │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │              │
│                    Pisces JavaScript SDK                      │
└────────────────────────────┼──────────────────────────────────┘
                             │ HTTP API (无需认证)
┌────────────────────────────┼──────────────────────────────────┐
│                    Pisces API层                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Experiment   │  │   Traffic    │  │    Data      │      │
│  │  Controller  │  │  Controller  │  │  Controller  │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │              │
└─────────┼──────────────────┼──────────────────┼──────────────┘
          │                  │                  │
┌─────────┼──────────────────┼──────────────────┼──────────────┐
│                    Pisces Service层                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Experiment   │  │   Traffic    │  │    Data      │      │
│  │   Service    │  │   Service    │  │   Service    │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │              │
│  ┌──────┴──────────────────┴──────────────────┴──────┐     │
│  │  MultiArmedBanditService  │  BayesianAnalysisService│    │
│  │  CausalInferenceService    │  HTEAnalysisService     │    │
│  └─────────────────────────────────────────────────────┘     │
└─────────┼──────────────────┼──────────────────┼──────────────┘
          │                  │                  │
┌─────────┼──────────────────┼──────────────────┼──────────────┐
│                   数据存储层                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   内存存储    │  │    Redis     │  │  Zookeeper   │      │
│  │  (默认存储)   │  │   (缓存)     │  │  (配置管理)   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、实验设计：二手手机交易价格提升

### 2.1 实验目标

#### 核心指标
- **主要指标**：平均成交价格（Average Transaction Price, ATP）
- **次要指标**：
  - 成交率（Transaction Rate = 成交数 / 咨询数）
  - 成交周期（从发布到成交的平均天数）

#### 目标设定
- **当前基线**：平均成交价格为市场价的75%（例如：iPhone 13 Pro市场价6000元，平台成交价4500元）
- **目标提升**：将平均成交价格提升至市场价的80%（即4800元，提升6.7%）
- **实验周期**：14天（考虑到交易周期较长）

#### 成功标准
- **统计显著**：成交价格提升≥5%，p值<0.05
- **业务显著**：成交率不下降（确保价格提升不是以牺牲成交量为代价）
- **稳定性**：价格提升在实验期间保持稳定

### 2.2 实验组设计

我们设计4个实验组，测试不同的优化策略：

#### A组（基准组）- 当前版本
**策略**：保持当前的商品展示方式

**配置**：
- 标题：简单的"成色+型号"格式
- 描述：基础的商品信息
- 价格展示：仅显示卖家报价
- 信任要素：基础的卖家信用展示

**示例展示**：
```
标题：95新 iPhone 13 Pro 256G
描述：自用手机，功能正常，外观良好
价格：4500元
```

**预期效果**：作为对比基准，CVR = 2.5%，平均成交价 = 4500元（市场价的75%）

#### B组（变体1）- 突出信任要素
**策略**：在标题和描述中突出"官方质检"、"无拆修"等信任要素

**配置**：
- 标题：添加"官方质检"标识
- 描述：详细说明质检结果、无拆修记录
- 价格展示：仅显示卖家报价
- 信任要素：展示官方质检报告

**示例展示**：
```
标题：95新 iPhone 13 Pro 256G 官方质检 无拆修
描述：官方质检认证，无拆修记录，功能完好，外观95新，电池健康度92%
价格：4500元
质检报告：✓ 官方质检合格 ✓ 无拆修 ✓ 功能正常
```

**预期效果**：CVR = 3.5%，平均成交价 = 4650元（提升3.3%）

#### C组（变体2）- 价格锚定
**策略**：展示市场参考价格，形成价格锚定效应

**配置**：
- 标题：同基准组
- 描述：同基准组
- 价格展示：显示卖家报价 + 市场参考价对比
- 信任要素：同基准组

**示例展示**：
```
标题：95新 iPhone 13 Pro 256G
描述：自用手机，功能正常，外观良好
价格：4500元
市场参考价：6000元（节省1500元，25%优惠）
```

**预期效果**：CVR = 3.0%，平均成交价 = 4725元（提升5.0%）

#### D组（变体3）- 组合策略
**策略**：组合使用信任要素 + 价格锚定

**配置**：
- 标题：添加"官方质检"标识
- 描述：详细说明质检结果
- 价格展示：显示卖家报价 + 市场参考价对比
- 信任要素：展示官方质检报告

**示例展示**：
```
标题：95新 iPhone 13 Pro 256G 官方质检 无拆修
描述：官方质检认证，无拆修记录，功能完好，外观95新，电池健康度92%
价格：4500元
市场参考价：6000元（节省1500元，25%优惠）
质检报告：✓ 官方质检合格 ✓ 无拆修 ✓ 功能正常
```

**预期效果**：CVR = 4.5%，平均成交价 = 4800元（提升6.7%，达到目标）

### 2.3 流量分配策略

考虑到需要快速验证效果，使用 **Thompson Sampling（汤普森采样）** 算法：

**初始流量分配**：
- A组（基准）：25%
- B组（信任要素）：25%
- C组（价格锚定）：25%
- D组（组合策略）：25%

**MAB算法自动调整**：随着实验进行，系统会将更多流量分配给成交价格更高的变体。

### 2.4 实验范围

- **商品类型**：二手iPhone（iPhone 12及以上型号）
- **价格区间**：3000-8000元
- **成色要求**：85新及以上
- **地域范围**：全国（或选择主要城市）
- **实验周期**：14天（如果使用贝叶斯分析，可能提前终止）

---

## 三、Pisces SDK使用指南

### 3.1 SDK概述

Pisces SDK是一个独立的、无需用户认证的A/B测试SDK，可以直接集成到您的项目中。

**特点**：
- ✅ **无需用户系统**：不依赖用户认证，使用唯一标识符（如设备ID、会话ID）即可
- ✅ **简单易用**：只需几个API调用即可完成集成
- ✅ **自动流量分配**：支持MAB算法自动优化流量分配
- ✅ **实时分析**：支持贝叶斯分析、因果推断、HTE分析

### 3.2 使用流程概览

使用Pisces SDK的完整流程：

```
┌─────────────────────────────────────────────────────────────┐
│  步骤1：创建实验（管理员操作）                                │
│  POST /api/experiments                                       │
│  → 返回实验ID，状态为 DRAFT                                  │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤2：启动实验（管理员操作）                                │
│  POST /api/experiments/{id}/start                           │
│  → 实验状态变为 RUNNING                                     │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤3：分配访客到实验组（前端/后端调用）                     │
│  POST /api/traffic/assign                                   │
│  → 返回实验组ID（A、B、C或D）                               │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤4：上报事件（前端/后端调用）                             │
│  POST /api/data/event                                        │
│  → 上报 VIEW、CLICK、CONVERT 事件                           │
└─────────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────────┐
│  步骤5：查看分析结果（管理员查看）                            │
│  GET /api/analysis/experiment/{id}/statistics               │
│  → 查看实验统计数据和分析结果                                │
└─────────────────────────────────────────────────────────────┘
```

**重要说明**：
- **步骤1-2**：通常由后端管理员或运营人员完成，创建并启动实验
- **步骤3-4**：由前端或后端应用在运行时调用，处理访客请求和数据收集
- **步骤5**：由管理员定期查看，用于分析实验效果和做出决策

**⚠️ 关键点**：必须先完成步骤1和步骤2（创建并启动实验），才能进行步骤3（分配访客）。如果实验未创建或未启动，分配访客的请求会失败。

### 3.3 实验管理API

#### 3.3.1 创建实验

在开始A/B测试之前，首先需要创建实验。

```http
POST /api/experiments
Content-Type: application/json

{
  "name": "二手手机交易价格提升实验",
  "description": "通过优化商品展示方式提升交易价格",
  "startTime": "2024-12-20T00:00:00",
  "endTime": "2025-01-03T23:59:59",
  "groups": [
    {
      "id": "A",
      "name": "基准组-当前版本",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号}",
        "showMarketPrice": false,
        "showQualityReport": false
      }
    },
    {
      "id": "B",
      "name": "变体1-突出信任要素",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号} 官方质检 无拆修",
        "showMarketPrice": false,
        "showQualityReport": true
      }
    }
  ],
  "traffic": {
    "totalTraffic": 1.0,
    "allocation": [
      {"group": "A", "ratio": 0.25},
      {"group": "B", "ratio": 0.25}
    ],
    "strategy": "THOMPSON_SAMPLING"
  }
}
```

**响应**：
```json
{
  "code": 200,
  "message": "实验创建成功",
  "data": {
    "id": "exp_price_001",
    "name": "二手手机交易价格提升实验",
    "status": "DRAFT"
  }
}
```

**保存实验ID**：`EXPERIMENT_ID="exp_price_001"`

#### 3.3.2 启动实验

实验创建后处于`DRAFT`状态，需要启动后才能开始分配访客和收集数据。

```http
POST /api/experiments/{experimentId}/start
```

**示例**：
```bash
POST /api/experiments/exp_price_001/start
```

**响应**：
```json
{
  "code": 200,
  "message": "实验启动成功"
}
```

**重要**：只有状态为`RUNNING`的实验才能分配访客和收集数据。

#### 3.3.3 获取实验配置

如果需要获取实验的详细配置信息（如实验组配置），可以调用：

```http
GET /api/experiments/{experimentId}
```

**响应**：
```json
{
  "code": 200,
  "data": {
    "id": "exp_price_001",
    "name": "二手手机交易价格提升实验",
    "status": "RUNNING",
    "groups": {
      "A": {
        "id": "A",
        "name": "基准组",
        "config": {
          "titleTemplate": "{成色} {型号}",
          "showMarketPrice": false,
          "showQualityReport": false
        }
      },
      "B": {
        "id": "B",
        "name": "变体1",
        "config": {
          "titleTemplate": "{成色} {型号} 官方质检 无拆修",
          "showMarketPrice": false,
          "showQualityReport": true
        }
      }
    }
  }
}
```

### 3.4 流量分配API

> **前置条件**：在调用流量分配API之前，必须先完成[3.3 实验管理API](#33-实验管理api)中的实验创建和启动步骤。只有状态为`RUNNING`的实验才能分配访客。

#### 3.4.1 分配访客到实验组

当访客首次访问您的应用时，需要调用此接口将访客分配到实验组。

```http
POST /api/traffic/assign
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345"  // 访客唯一标识（设备ID、会话ID等）
}
```

**响应**：
```json
{
  "code": 200,
  "data": "B"  // 实验组ID（A、B、C或D）
}
```

**重要说明**：
1. **前置条件**：必须先完成实验创建（3.3.1）和实验启动（3.3.2），确保实验状态为`RUNNING`
2. **首次访问时分配**：每个访客首次访问时调用此接口，系统会根据流量分配策略（如Thompson Sampling）将访客分配到某个实验组
3. **结果会缓存**：同一个`visitorId`和`experimentId`的组合，后续调用会返回相同的组ID，确保访客在整个实验期间始终在同一个组
4. **获取组配置**：分配后，可以调用`GET /api/experiments/{experimentId}`获取实验组配置，用于渲染页面

### 3.5 数据上报API

> **前置条件**：在调用数据上报API之前，必须先完成：
> 1. [3.3 实验管理API](#33-实验管理api)：创建并启动实验
> 2. [3.4 流量分配API](#34-流量分配api)：分配访客到实验组

#### 3.5.1 上报事件

```http
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "VIEW",  // VIEW, CLICK, CONVERT
  "eventName": "product_view",
  "properties": {
    "productId": "iphone_001",
    "productPrice": 4500,
    "marketPrice": 6000
  }
}
```

**事件类型**：
- `VIEW`：浏览事件（如商品详情页浏览）
- `CLICK`：点击事件（如咨询卖家、加入购物车）
- `CONVERT`：转化事件（如交易完成，这是核心指标）

**重要**：CONVERT事件中的`transactionPrice`是核心指标，系统会用于计算各组的平均成交价格和更新MAB算法奖励。

### 3.6 JavaScript SDK

> **前置条件**：在使用SDK之前，请确保：
> 1. 实验已创建并启动（通常由后端管理员完成，参考[3.3 实验管理API](#33-实验管理api)）
> 2. 已获取实验ID（如`exp_price_001`）

#### 3.6.1 安装和使用

```html
<script src="https://cdn.yourdomain.com/pisces-sdk.js"></script>
<script>
  // 【重要】首先确保实验已创建并启动（通常由后端管理员完成）
  // 实验ID: exp_price_001
  
  // 初始化SDK
  const pisces = new PiscesSDK({
    apiBaseUrl: 'http://localhost:8080/api',
    experimentId: 'exp_price_001',  // 使用已创建并启动的实验ID
    visitorId: getVisitorId()  // 获取访客唯一标识（设备ID、会话ID等）
  });

  // 获取实验组（SDK内部会调用 /api/traffic/assign）
  pisces.getGroup().then(groupId => {
    console.log('访客所在组:', groupId);
    // 根据组ID渲染页面
    renderPage(groupId);
  });

  // 上报浏览事件
  pisces.reportView({
    productId: 'iphone_001',
    productPrice: 4500,
    marketPrice: 6000
  });

  // 交易完成时上报
  pisces.reportTransaction({
    transactionPrice: 4800,  // 实际成交价格（核心指标）
    listPrice: 4500,
    marketPrice: 6000
  });
</script>
```

#### 3.6.2 完整SDK代码

```javascript
/**
 * Pisces A/B测试 SDK
 * 无需用户认证，使用访客ID即可
 */
class PiscesSDK {
  constructor(config) {
    this.apiBaseUrl = config.apiBaseUrl || 'http://localhost:8080/api';
    this.experimentId = config.experimentId;
    this.visitorId = config.visitorId;  // 访客唯一标识
    
    // 缓存实验组ID
    this.groupIdCache = null;
    this.experimentConfigCache = null;
  }

  /**
   * 获取访客所在的实验组
   * @returns {Promise<string>} 实验组ID
   */
  async getGroup() {
    if (this.groupIdCache) {
      return this.groupIdCache;
    }
    
    try {
      const response = await fetch(`${this.apiBaseUrl}/traffic/assign`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          experimentId: this.experimentId,
          visitorId: this.visitorId
        })
      });
      
      const data = await response.json();
      this.groupIdCache = data.data;
      return data.data;
    } catch (error) {
      console.error('获取实验组失败:', error);
      return null;
    }
  }

  /**
   * 获取实验配置
   * @returns {Promise<object>} 实验配置
   */
  async getExperimentConfig() {
    if (this.experimentConfigCache) {
      return this.experimentConfigCache;
    }
    
    try {
      const response = await fetch(`${this.apiBaseUrl}/experiments/${this.experimentId}`);
      const data = await response.json();
      this.experimentConfigCache = data.data;
      return data.data;
    } catch (error) {
      console.error('获取实验配置失败:', error);
      return null;
    }
  }

  /**
   * 获取当前组的配置
   * @returns {Promise<object>} 组配置
   */
  async getGroupConfig() {
    const groupId = await this.getGroup();
    if (!groupId) return null;
    
    const experiment = await this.getExperimentConfig();
    if (!experiment || !experiment.groups) return null;
    
    return experiment.groups[groupId]?.config || null;
  }

  /**
   * 上报事件
   * @param {string} eventType 事件类型 (VIEW, CLICK, CONVERT)
   * @param {string} eventName 事件名称
   * @param {object} properties 事件属性
   */
  async reportEvent(eventType, eventName, properties) {
    try {
      const response = await fetch(`${this.apiBaseUrl}/data/event`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          experimentId: this.experimentId,
          visitorId: this.visitorId,
          eventType: eventType,
          eventName: eventName,
          properties: properties
        })
      });
      
      return await response.json();
    } catch (error) {
      console.error('上报事件失败:', error);
    }
  }

  /**
   * 上报浏览事件
   * @param {object} productData 商品数据
   */
  async reportView(productData) {
    return this.reportEvent('VIEW', 'product_view', {
      productId: productData.productId,
      productPrice: productData.productPrice,
      marketPrice: productData.marketPrice,
      productModel: productData.productModel,
      condition: productData.condition
    });
  }

  /**
   * 上报咨询事件
   * @param {object} clickData 点击数据
   */
  async reportClick(clickData) {
    return this.reportEvent('CLICK', 'contact_seller', {
      productId: clickData.productId,
      productPrice: clickData.productPrice
    });
  }

  /**
   * 上报成交事件（关键指标）
   * @param {object} transactionData 交易数据
   */
  async reportTransaction(transactionData) {
    const priceRatio = transactionData.marketPrice > 0 
      ? transactionData.transactionPrice / transactionData.marketPrice 
      : 0;
    
    return this.reportEvent('CONVERT', 'transaction_completed', {
      transactionId: transactionData.transactionId,
      productId: transactionData.productId,
      transactionPrice: transactionData.transactionPrice,  // 实际成交价格（核心指标）
      listPrice: transactionData.listPrice,
      marketPrice: transactionData.marketPrice,
      priceRatio: priceRatio
    });
  }
}

// 导出SDK
if (typeof module !== 'undefined' && module.exports) {
  module.exports = PiscesSDK;
}
```

### 3.7 Java SDK

#### 3.7.1 Maven依赖

```xml
<dependency>
    <groupId>com.pisces</groupId>
    <artifactId>pisces-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 3.7.2 使用示例

```java
package com.yourcompany.service;

import com.pisces.sdk.PiscesClient;
import com.pisces.sdk.model.ExperimentConfig;
import org.springframework.stereotype.Service;

@Service
public class ExperimentService {
    
    private final PiscesClient piscesClient;
    private final String experimentId = "exp_price_001";
    
    public ExperimentService() {
        this.piscesClient = new PiscesClient("http://localhost:8080/api");
    }
    
    /**
     * 获取访客所在的实验组
     */
    public String getGroup(String visitorId) {
        return piscesClient.assignGroup(experimentId, visitorId);
    }
    
    /**
     * 获取实验组配置
     */
    public ExperimentConfig getGroupConfig(String visitorId) {
        String groupId = getGroup(visitorId);
        ExperimentConfig experiment = piscesClient.getExperiment(experimentId);
        return experiment.getGroups().get(groupId);
    }
    
    /**
     * 上报浏览事件
     */
    public void reportView(String visitorId, Map<String, Object> productData) {
        piscesClient.reportEvent(experimentId, visitorId, "VIEW", "product_view", productData);
    }
    
    /**
     * 上报成交事件
     */
    public void reportTransaction(String visitorId, Map<String, Object> transactionData) {
        piscesClient.reportEvent(experimentId, visitorId, "CONVERT", "transaction_completed", transactionData);
    }
}
```

---

## 四、后端集成

### 4.1 添加依赖

```xml
<dependency>
    <groupId>com.pisces</groupId>
    <artifactId>pisces-service</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 4.2 配置

```yaml
# application.yml
pisces:
  api:
    baseUrl: http://localhost:8080/api
  experiments:
    price-optimization:
      id: exp_price_001
      enabled: true
```

### 4.3 创建实验服务封装

```java
package com.yourcompany.service;

import com.pisces.service.service.TrafficService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentService;
import com.pisces.common.response.ExperimentResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.HashMap;

/**
 * 实验服务封装类（无用户系统版本）
 * 使用visitorId（访客ID）替代userId
 */
@Slf4j
@Service
public class ExperimentIntegrationService {
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired
    private DataService dataService;
    
    @Autowired
    private ExperimentService experimentService;
    
    @Value("${pisces.experiments.price-optimization.id}")
    private String priceExperimentId;
    
    /**
     * 获取访客所在的实验组
     * @param visitorId 访客唯一标识（设备ID、会话ID等）
     * @return 实验组ID
     */
    public String getVisitorGroup(String visitorId) {
        try {
            return trafficService.getUserGroup(priceExperimentId, visitorId);
        } catch (Exception e) {
            log.error("获取访客实验组失败: experimentId={}, visitorId={}", 
                    priceExperimentId, visitorId, e);
            return "A"; // 失败时返回默认组
        }
    }
    
    /**
     * 获取实验组配置
     * @param visitorId 访客ID
     * @return 实验组配置
     */
    public Map<String, Object> getGroupConfig(String visitorId) {
        try {
            String groupId = getVisitorGroup(visitorId);
            ExperimentResponse response = experimentService.getExperiment(priceExperimentId);
            if (response != null && response.getGroups() != null) {
                var group = response.getGroups().get(groupId);
                return group != null ? group.getConfig() : null;
            }
        } catch (Exception e) {
            log.error("获取实验组配置失败: visitorId={}", visitorId, e);
        }
        return null;
    }
    
    /**
     * 上报浏览事件
     * @param visitorId 访客ID
     * @param productData 商品数据
     */
    public void reportViewEvent(String visitorId, Map<String, Object> productData) {
        try {
            Map<String, Object> properties = new HashMap<>();
            properties.put("productId", productData.get("productId"));
            properties.put("productPrice", productData.get("price"));
            properties.put("marketPrice", productData.get("marketPrice"));
            properties.put("productModel", productData.get("model"));
            properties.put("condition", productData.get("condition"));
            
            dataService.reportEvent(
                priceExperimentId,
                visitorId,  // 使用visitorId替代userId
                "VIEW",
                "product_detail_view",
                properties
            );
        } catch (Exception e) {
            log.error("上报浏览事件失败: visitorId={}", visitorId, e);
        }
    }
    
    /**
     * 上报成交事件（关键指标）
     * @param visitorId 访客ID
     * @param transactionData 交易数据
     */
    public void reportTransactionEvent(String visitorId, Map<String, Object> transactionData) {
        try {
            Double transactionPrice = (Double) transactionData.get("transactionPrice");
            Double marketPrice = (Double) transactionData.get("marketPrice");
            Double priceRatio = marketPrice > 0 ? transactionPrice / marketPrice : 0.0;
            
            Map<String, Object> properties = new HashMap<>();
            properties.put("transactionId", transactionData.get("transactionId"));
            properties.put("productId", transactionData.get("productId"));
            properties.put("transactionPrice", transactionPrice);  // 核心指标
            properties.put("listPrice", transactionData.get("listPrice"));
            properties.put("marketPrice", marketPrice);
            properties.put("priceRatio", priceRatio);
            
            dataService.reportEvent(
                priceExperimentId,
                visitorId,  // 使用visitorId替代userId
                "CONVERT",
                "transaction_completed",
                properties
            );
            
            log.info("上报成交事件: visitorId={}, price={}, ratio={}", 
                    visitorId, transactionPrice, priceRatio);
        } catch (Exception e) {
            log.error("上报成交事件失败: visitorId={}", visitorId, e);
        }
    }
}
```

### 4.4 在业务代码中使用

#### 商品详情页Controller

```java
package com.yourcompany.controller;

import com.yourcompany.service.ExperimentIntegrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    
    @Autowired
    private ExperimentIntegrationService experimentService;
    
    /**
     * 获取商品详情（集成实验）
     * @param productId 商品ID
     * @param visitorId 访客ID（从Cookie、Header或参数中获取）
     */
    @GetMapping("/{productId}")
    public Map<String, Object> getProductDetail(
            @PathVariable String productId,
            @RequestParam(required = false) String visitorId) {
        
        // 如果没有visitorId，生成一个（或从Cookie中获取）
        if (visitorId == null || visitorId.isEmpty()) {
            visitorId = generateVisitorId();  // 您的生成逻辑
        }
        
        // 1. 获取访客所在的实验组
        String groupId = experimentService.getVisitorGroup(visitorId);
        
        // 2. 获取商品数据
        Map<String, Object> product = getProductFromCache(productId);  // 从缓存或业务系统获取商品信息
        
        // 3. 获取实验组配置
        Map<String, Object> config = experimentService.getGroupConfig(visitorId);
        
        // 4. 构建响应
        Map<String, Object> result = new HashMap<>();
        result.put("product", product);
        result.put("groupId", groupId);
        result.put("visitorId", visitorId);  // 返回visitorId，前端保存到Cookie
        
        // 5. 根据实验组配置应用展示策略
        if (config != null) {
            applyExperimentConfig(result, product, config);
        }
        
        // 6. 上报浏览事件
        Map<String, Object> productData = new HashMap<>();
        productData.put("productId", productId);
        productData.put("price", product.get("price"));
        productData.put("marketPrice", product.get("marketPrice"));
        productData.put("model", product.get("model"));
        productData.put("condition", product.get("condition"));
        experimentService.reportViewEvent(visitorId, productData);
        
        return result;
    }
    
    /**
     * 应用实验配置
     */
    private void applyExperimentConfig(Map<String, Object> result, 
                                      Map<String, Object> product,
                                      Map<String, Object> config) {
        // 应用标题模板
        if (config.containsKey("titleTemplate")) {
            String titleTemplate = (String) config.get("titleTemplate");
            String title = titleTemplate
                .replace("{成色}", (String) product.get("condition"))
                .replace("{型号}", (String) product.get("model"));
            result.put("title", title);
        }
        
        // 市场价对比
        if ((Boolean) config.getOrDefault("showMarketPrice", false)) {
            Double marketPrice = (Double) product.get("marketPrice");
            Double price = (Double) product.get("price");
            Map<String, Object> comparison = new HashMap<>();
            comparison.put("marketPrice", marketPrice);
            comparison.put("savings", marketPrice - price);
            comparison.put("savingsPercent", (marketPrice - price) / marketPrice * 100);
            result.put("priceComparison", comparison);
        }
        
        // 质检报告
        if ((Boolean) config.getOrDefault("showQualityReport", false)) {
            result.put("qualityReport", getQualityReport((String) product.get("productId")));
        }
    }
    
    private String generateVisitorId() {
        // 生成唯一访客ID（可以使用UUID、设备ID等）
        return "visitor_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    
    private Map<String, Object> getQualityReport(String productId) {
        // 从缓存或业务系统获取质检报告
        Map<String, Object> report = new HashMap<>();
        report.put("officialQualityCheck", true);
        report.put("noRepair", true);
        report.put("functionNormal", true);
        return report;
    }
}
```

#### 交易服务

```java
package com.yourcompany.service;

import com.yourcompany.service.ExperimentIntegrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class TransactionService {
    
    @Autowired
    private ExperimentIntegrationService experimentService;
    
    /**
     * 处理交易完成（集成实验数据上报）
     * @param transactionId 交易ID
     * @param visitorId 访客ID
     * @param productId 商品ID
     * @param transactionPrice 实际成交价格（核心指标）
     */
    @Transactional
    public void completeTransaction(String transactionId, String visitorId,
                                   String productId, Double transactionPrice) {
        // 1. 处理交易业务逻辑
        // ... 您的交易处理代码 ...
        
        // 2. 获取商品信息
        Map<String, Object> product = getProduct(productId);
        Double listPrice = (Double) product.get("price");
        Double marketPrice = (Double) product.get("marketPrice");
        
        // 3. 上报成交事件（关键指标）
        Map<String, Object> transactionData = new HashMap<>();
        transactionData.put("transactionId", transactionId);
        transactionData.put("productId", productId);
        transactionData.put("transactionPrice", transactionPrice);  // 核心指标
        transactionData.put("listPrice", listPrice);
        transactionData.put("marketPrice", marketPrice);
        
        experimentService.reportTransactionEvent(visitorId, transactionData);
    }
}
```

---

## 五、前端集成

### 5.1 HTML页面集成

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>商品详情页</title>
  <script src="https://cdn.yourdomain.com/pisces-sdk.js"></script>
</head>
<body>
  <div id="product-detail">
    <h1 id="product-title">加载中...</h1>
    <div id="product-price">加载中...</div>
    <div id="market-price" style="display: none;"></div>
    <div id="quality-report" style="display: none;"></div>
  </div>

  <script>
    // 获取或生成访客ID
    function getVisitorId() {
      let visitorId = localStorage.getItem('pisces_visitor_id');
      if (!visitorId) {
        visitorId = 'visitor_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        localStorage.setItem('pisces_visitor_id', visitorId);
      }
      return visitorId;
    }

    // 初始化SDK
    const pisces = new PiscesSDK({
      apiBaseUrl: 'http://localhost:8080/api',
      experimentId: 'exp_price_001',
      visitorId: getVisitorId()
    });

    const productId = getProductIdFromUrl();

    // 加载商品详情
    async function loadProduct() {
      try {
        // 1. 获取实验组
        const groupId = await pisces.getGroup();
        
        // 2. 获取商品数据（后端会根据实验组返回不同配置）
        const response = await fetch(`/api/products/${productId}?visitorId=${pisces.visitorId}`);
        const data = await response.json();
        
        // 3. 渲染页面
        renderProduct(data, groupId);
        
        // 4. 上报浏览事件
        await pisces.reportView({
          productId: productId,
          productPrice: data.product.price,
          marketPrice: data.product.marketPrice,
          productModel: data.product.model,
          condition: data.product.condition
        });
      } catch (error) {
        console.error('加载商品失败:', error);
      }
    }

    // 渲染商品页面
    function renderProduct(data, groupId) {
      document.getElementById('product-title').textContent = data.title || data.product.title;
      document.getElementById('product-price').textContent = `¥${data.product.price}`;
      
      // 显示市场价对比（C组或D组）
      if (data.priceComparison) {
        document.getElementById('market-price').style.display = 'block';
        document.getElementById('market-price').innerHTML = `
          <p>市场参考价：¥${data.priceComparison.marketPrice}</p>
          <p>节省：¥${data.priceComparison.savings}（${data.priceComparison.savingsPercent.toFixed(1)}%优惠）</p>
        `;
      }
      
      // 显示质检报告（B组或D组）
      if (data.qualityReport) {
        document.getElementById('quality-report').style.display = 'block';
        document.getElementById('quality-report').innerHTML = `
          <h3>质检报告</h3>
          <p>✓ 官方质检合格</p>
          <p>✓ 无拆修</p>
          <p>✓ 功能正常</p>
        `;
      }
    }

    // 交易完成时调用
    async function onTransactionComplete(transaction) {
      await pisces.reportTransaction({
        transactionId: transaction.id,
        productId: transaction.productId,
        transactionPrice: transaction.price,  // 实际成交价格（核心指标）
        listPrice: transaction.listPrice,
        marketPrice: transaction.marketPrice
      });
    }

    loadProduct();
  </script>
</body>
</html>
```

---

## 六、数据存储设计

### 6.1 存储架构

Pisces系统采用**内存存储**作为默认存储方式，无需数据库。所有数据存储在内存中，支持可选使用Redis进行缓存优化。

#### 6.1.1 内存存储结构

```java
// 事件存储（实验ID:组ID -> 事件列表）
ConcurrentHashMap<String, List<Event>> eventStore

// 事件计数器（实验ID:组ID -> 事件类型 -> 数量）
ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> eventCounters

// 访客集合（实验ID:组ID -> 访客ID集合）
ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> visitorSets

// 访客分组缓存（访客ID -> 实验ID -> 组ID）
ConcurrentHashMap<String, Map<String, String>> userGroupCache

// 实验配置缓存（实验ID -> 实验元数据）
ConcurrentHashMap<String, ExperimentMetadata> experimentCache
```

#### 6.1.2 Redis缓存（可选）

如果配置了Redis，系统会使用Redis进行以下优化：
- 访客分组缓存：加速访客分组查询
- 实验配置缓存：加速实验配置读取
- 统计数据缓存：缓存分析结果，减少计算

#### 6.1.3 Zookeeper配置管理（可选）

如果配置了Zookeeper，系统会使用Zookeeper进行：
- 实验配置的分布式存储
- 配置的实时下发和版本管理
- 多实例配置同步

### 6.2 数据持久化（可选）

如果需要数据持久化，可以：
1. **集成消息队列**：将事件数据发送到Kafka、RabbitMQ等消息队列
2. **集成数据仓库**：定期将统计数据导出到数据仓库
3. **自定义存储**：实现`DataService`接口，将数据存储到自定义存储系统

**注意**：系统默认不提供持久化功能，所有数据存储在内存中。应用重启后数据会丢失，适合实验数据的实时处理场景。

---

## 七、完整实施流程

### 7.1 创建实验

#### 7.1.1 实验配置

```bash
POST /api/experiments
Content-Type: application/json

{
  "name": "二手手机交易价格提升实验",
  "description": "通过优化商品展示方式（信任要素、价格锚定）提升交易价格，目标将平均成交价格从市场价的75%提升至80%",
  "startTime": "2024-12-20T00:00:00",
  "endTime": "2025-01-03T23:59:59",
  "groups": [
    {
      "id": "A",
      "name": "基准组-当前版本",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号}",
        "showMarketPrice": false,
        "showQualityReport": false,
        "trustElements": ["sellerCredit"]
      }
    },
    {
      "id": "B",
      "name": "变体1-突出信任要素",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号} 官方质检 无拆修",
        "showMarketPrice": false,
        "showQualityReport": true,
        "trustElements": ["sellerCredit", "qualityReport", "noRepair"]
      }
    },
    {
      "id": "C",
      "name": "变体2-价格锚定",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号}",
        "showMarketPrice": true,
        "showQualityReport": false,
        "trustElements": ["sellerCredit"]
      }
    },
    {
      "id": "D",
      "name": "变体3-组合策略",
      "trafficRatio": 0.25,
      "config": {
        "titleTemplate": "{成色} {型号} 官方质检 无拆修",
        "showMarketPrice": true,
        "showQualityReport": true,
        "trustElements": ["sellerCredit", "qualityReport", "noRepair"]
      }
    }
  ],
  "traffic": {
    "totalTraffic": 1.0,
    "allocation": [
      {"group": "A", "ratio": 0.25},
      {"group": "B", "ratio": 0.25},
      {"group": "C", "ratio": 0.25},
      {"group": "D", "ratio": 0.25}
    ],
    "strategy": "THOMPSON_SAMPLING"
  },
  "whitelist": [],
  "blacklist": []
}
```

**响应**：
```json
{
  "code": 200,
  "message": "实验创建成功",
  "data": {
    "id": "exp_price_001",
    "name": "二手手机交易价格提升实验",
    "status": "DRAFT"
  }
}
```

**保存实验ID**：`EXPERIMENT_ID="exp_price_001"`

#### 7.1.2 启动实验

```bash
POST /api/experiments/${EXPERIMENT_ID}/start
```

**响应**：
```json
{
  "code": 200,
  "message": "实验启动成功"
}
```

**重要**：实验启动后，状态变为`RUNNING`，此时可以开始分配访客和收集数据。

### 7.2 分配访客到实验组

在访客访问商品详情页时，首先需要将访客分配到实验组。这一步应该在所有事件上报之前完成。

#### 7.2.1 分配访客到实验组

```bash
POST /api/traffic/assign
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345"  // 访客唯一标识（设备ID、会话ID、Cookie ID等）
}
```

**响应**：
```json
{
  "code": 200,
  "data": "D"  // 返回实验组ID（A、B、C或D）
}
```

**重要说明**：
1. **首次访问时分配**：每个访客首次访问时调用此接口，系统会根据流量分配策略（如Thompson Sampling）将访客分配到某个实验组
2. **结果会缓存**：同一个`visitorId`和`experimentId`的组合，后续调用会返回相同的组ID，确保访客在整个实验期间始终在同一个组
3. **获取组配置**：分配后，可以调用`GET /api/experiments/exp_price_001`获取实验组配置，用于渲染页面

#### 7.2.2 获取实验组配置（可选）

如果需要获取实验组的详细配置信息（如是否显示市场价、质检报告等），可以调用：

```bash
GET /api/experiments/exp_price_001
```

**响应**：
```json
{
  "code": 200,
  "data": {
    "id": "exp_price_001",
    "name": "二手手机交易价格提升实验",
    "status": "RUNNING",
    "groups": {
      "D": {
        "id": "D",
        "name": "变体3-组合策略",
        "config": {
          "titleTemplate": "{成色} {型号} 官方质检 无拆修",
          "showMarketPrice": true,
          "showQualityReport": true,
          "trustElements": ["sellerCredit", "qualityReport", "noRepair"]
        }
      }
    }
  }
}
```

### 7.3 数据收集流程

#### 7.3.1 访客浏览商品（VIEW事件）

```bash
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "VIEW",
  "eventName": "product_detail_view",
  "properties": {
    "productId": "iphone_001",
    "productPrice": 4500,
    "marketPrice": 6000,
    "productModel": "iPhone 13 Pro",
    "condition": "95新"
  }
}
```

#### 7.3.2 访客咨询商品（CLICK事件）

```bash
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "CLICK",
  "eventName": "contact_seller",
  "properties": {
    "productId": "iphone_001",
    "productPrice": 4500
  }
}
```

#### 7.3.3 访客成交（CONVERT事件）- 关键指标

```bash
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "CONVERT",
  "eventName": "transaction_completed",
  "properties": {
    "productId": "iphone_001",
    "transactionPrice": 4800,  // 实际成交价格（关键指标）
    "listPrice": 4500,         // 卖家报价
    "marketPrice": 6000,       // 市场参考价
    "priceRatio": 0.80,        // 成交价/市场价 = 80%
    "transactionDate": "2024-12-22T10:30:00"
  }
}
```

**重要**：CONVERT事件中的`transactionPrice`是核心指标，系统会：
1. 计算各组的平均成交价格
2. 更新MAB算法的奖励数据（价格越高，奖励越大）

### 7.4 完整数据收集流程

```
1. 访客访问商品详情页
   ↓
2. 生成或获取visitorId（从Cookie/localStorage）
   ↓
3. 【关键步骤】调用 /api/traffic/assign 分配访客到实验组
   ↓
4. 获取实验组配置（调用 /api/experiments/{experimentId}）
   ↓
5. 根据组ID和配置渲染页面（显示市场价、质检报告等）
   ↓
6. 上报VIEW事件（访客浏览商品）
   ↓
7. 访客咨询 → 上报CLICK事件
   ↓
8. 访客成交 → 上报CONVERT事件（包含实际成交价格）
   ↓
9. 系统自动更新MAB算法奖励（价格越高，奖励越大）
   ↓
10. 系统自动调整流量分配（将更多流量分配给价格更高的变体）
```

**流程说明**：
- **步骤3是关键**：必须先分配访客到实验组，才能确保后续事件上报时能正确关联到实验组
- **步骤4可选**：如果需要根据实验组配置动态渲染页面，需要获取配置；如果配置已在前端硬编码，可跳过
- **步骤6-8**：根据访客行为按顺序上报事件

### 7.5 预期数据示例

假设实验运行7天后，收集到以下数据：

| 组 | 曝光数(VIEW) | 咨询数(CLICK) | 成交数(CONVERT) | 平均成交价 | 成交率 | 价格/市场价 |
|---|---|---|---|---|---|---|
| A（基准） | 5000 | 500 | 100 | 4500元 | 10% | 75% |
| B（变体1） | 5000 | 550 | 110 | 4650元 | 11% | 77.5% |
| C（变体2） | 5000 | 520 | 105 | 4725元 | 10.5% | 78.75% |
| D（变体3） | 5000 | 580 | 120 | 4800元 | 12% | 80% |

**初步结论**：
- A组（基准）：平均成交价4500元（市场价的75%）
- B组（信任要素）：平均成交价4650元（提升3.3%）
- C组（价格锚定）：平均成交价4725元（提升5%）
- **D组（组合策略）**：平均成交价4800元（提升6.7%，达到目标）

---

## 八、代码示例

### 8.1 完整的商品详情页实现

```java
@RestController
@RequestMapping("/api/products")
public class ProductController {
    
    @Autowired
    private ExperimentIntegrationService experimentService;
    
    @GetMapping("/{productId}")
    public Map<String, Object> getProductDetail(
            @PathVariable String productId,
            @RequestParam(required = false) String visitorId,
            HttpServletRequest request) {
        
        // 获取或生成visitorId
        if (visitorId == null || visitorId.isEmpty()) {
            visitorId = getVisitorIdFromCookie(request);
            if (visitorId == null) {
                visitorId = generateVisitorId();
            }
        }
        
        // 获取实验组
        String groupId = experimentService.getVisitorGroup(visitorId);
        
        // 获取商品数据
        Map<String, Object> product = getProduct(productId);
        
        // 获取实验配置
        Map<String, Object> config = experimentService.getGroupConfig(visitorId);
        
        // 构建响应
        Map<String, Object> result = new HashMap<>();
        result.put("product", product);
        result.put("groupId", groupId);
        result.put("visitorId", visitorId);
        
        // 应用实验配置
        if (config != null) {
            applyConfig(result, product, config);
        }
        
        // 上报浏览事件
        Map<String, Object> productData = new HashMap<>();
        productData.put("productId", productId);
        productData.put("price", product.get("price"));
        productData.put("marketPrice", product.get("marketPrice"));
        experimentService.reportViewEvent(visitorId, productData);
        
        return result;
    }
    
    private String getVisitorIdFromCookie(HttpServletRequest request) {
        // 从Cookie中获取visitorId
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("pisces_visitor_id".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
```

---

## 九、数据分析与决策

### 9.1 基础统计分析

#### 查看实验统计数据

```bash
GET /api/analysis/experiment/${EXPERIMENT_ID}/statistics
```

**响应示例**（实验运行7天后）：

```json
{
  "code": 200,
  "data": {
    "experimentId": "exp_price_001",
    "groupStatistics": {
      "A": {
        "groupId": "A",
        "userCount": 5000,
        "eventCounts": {
          "VIEW": 5000,
          "CLICK": 500,
          "CONVERT": 100
        },
        "conversionRate": 0.10,
        "averageTransactionPrice": 4500,
        "priceRatio": 0.75
      },
      "B": {
        "groupId": "B",
        "userCount": 5000,
        "eventCounts": {
          "VIEW": 5000,
          "CLICK": 550,
          "CONVERT": 110
        },
        "conversionRate": 0.11,
        "averageTransactionPrice": 4650,
        "priceRatio": 0.775
      },
      "C": {
        "groupId": "C",
        "userCount": 5000,
        "eventCounts": {
          "VIEW": 5000,
          "CLICK": 520,
          "CONVERT": 105
        },
        "conversionRate": 0.105,
        "averageTransactionPrice": 4725,
        "priceRatio": 0.7875
      },
      "D": {
        "groupId": "D",
        "userCount": 5000,
        "eventCounts": {
          "VIEW": 5000,
          "CLICK": 580,
          "CONVERT": 120
        },
        "conversionRate": 0.12,
        "averageTransactionPrice": 4800,
        "priceRatio": 0.80
      }
    }
  }
}
```

**初步结论**：
- A组（基准）：平均成交价4500元（市场价的75%）
- B组（信任要素）：平均成交价4650元（提升3.3%）
- C组（价格锚定）：平均成交价4725元（提升5%）
- **D组（组合策略）**：平均成交价4800元（提升6.7%，达到目标）

### 9.2 贝叶斯分析（价格提升概率）

```bash
GET /api/analysis/experiment/${EXPERIMENT_ID}/bayesian
```

**响应示例**：

```json
{
  "code": 200,
  "data": {
    "baselineGroup": "A",
    "winRates": {
      "B": 0.85,  // B组价格高于A组的概率为85%
      "C": 0.92,  // C组价格高于A组的概率为92%
      "D": 0.98   // D组价格高于A组的概率为98%
    },
    "earlyStopInfo": {
      "D": {
        "winRate": 0.98,
        "threshold": 0.95,
        "canStop": true,
        "reason": "正向显著：D组价格高于基准的概率达到98%，可以提前终止实验并全量上线"
      }
    }
  }
}
```

**关键发现**：
- D组价格高于基准的概率达到**98%**，超过95%阈值
- **可以提前终止实验**，将D组策略全量上线

### 9.3 判断是否可以提前终止

```bash
GET /api/analysis/experiment/${EXPERIMENT_ID}/early-stop?variantGroupId=D&baselineGroupId=A
```

**响应**：
```json
{
  "code": 200,
  "data": {
    "winRate": 0.98,
    "threshold": 0.95,
    "canStop": true,
    "reason": "正向显著：D组价格高于基准的概率达到98%，可以提前终止实验并全量上线"
  }
}
```

### 9.4 因果推断分析（DID方法）

由于交易价格可能受时间因素影响（如周末、节假日），使用DID方法剥离时间趋势：

```bash
POST /api/analysis/experiment/${EXPERIMENT_ID}/causal-inference?method=DID&treatmentGroupId=D&controlGroupId=A
Content-Type: application/json

{
  "beforePeriodStart": "2024-12-20 00:00:00",
  "beforePeriodEnd": "2024-12-23 23:59:59",
  "afterPeriodStart": "2024-12-24 00:00:00",
  "afterPeriodEnd": "2024-12-27 23:59:59"
}
```

**响应示例**：

```json
{
  "code": 200,
  "data": {
    "method": "DID",
    "didEstimate": 300,  // 在控制时间趋势后，D组价格提升300元
    "treatmentBefore": 4500,
    "treatmentAfter": 4800,
    "controlBefore": 4500,
    "controlAfter": 4520,  // 基准组受时间因素影响，价格略有提升
    "treatmentDiff": 300,
    "controlDiff": 20,
    "standardError": 50,
    "tStatistic": 6.0,
    "pValue": 0.001,
    "isSignificant": true,
    "interpretation": "在控制了时间趋势和两组人群固有差异后，D组策略带来300元的真实价格提升，统计显著"
  }
}
```

### 9.5 HTE分析（识别价格敏感用户群体）

分析不同用户群体对价格提升策略的反应：

```bash
POST /api/analysis/experiment/${EXPERIMENT_ID}/hte?treatmentGroupId=D&controlGroupId=A
Content-Type: application/json

["buyerAge", "buyerCredit", "purchaseHistory", "deviceType"]
```

**响应示例**：

```json
{
  "code": 200,
  "data": {
    "overallATE": 300,
    "cateByGroup": {
      "age_25_35": {
        "treatmentEffect": 350,  // 25-35岁用户价格提升350元
        "userCount": 3000,
        "confidence": 0.90
      },
      "age_35_45": {
        "treatmentEffect": 250,  // 35-45岁用户价格提升250元
        "userCount": 2000,
        "confidence": 0.85
      },
      "high_credit": {
        "treatmentEffect": 400,  // 高信用用户价格提升400元
        "userCount": 2500,
        "confidence": 0.92
      },
      "low_credit": {
        "treatmentEffect": 200,  // 低信用用户价格提升200元
        "userCount": 2500,
        "confidence": 0.78
      }
    },
    "keyFindings": [
      "25-35岁用户对价格提升策略更敏感（提升350元 vs 整体300元）",
      "高信用用户对价格提升策略更敏感（提升400元 vs 整体300元）",
      "建议对高敏感群体（25-35岁高信用用户）优先使用D组策略"
    ]
  }
}
```

### 9.6 识别敏感用户群体

```bash
POST /api/analysis/experiment/${EXPERIMENT_ID}/sensitive-groups?treatmentGroupId=D&controlGroupId=A
Content-Type: application/json

["buyerAge", "buyerCredit", "purchaseHistory"]
```

**响应示例**：

```json
{
  "code": 200,
  "data": {
    "highSensitive": {
      "criteria": "priceIncrease > 350元",
      "estimatedEffect": 400,
      "userCount": 2000,
      "userPercentage": 40.0,
      "recommendation": "优先使用D组策略，预期价格提升400元"
    },
    "mediumSensitive": {
      "criteria": "250元 < priceIncrease <= 350元",
      "estimatedEffect": 300,
      "userCount": 2000,
      "userPercentage": 40.0,
      "recommendation": "可使用D组策略，预期价格提升300元"
    },
    "lowSensitive": {
      "criteria": "priceIncrease <= 250元",
      "estimatedEffect": 200,
      "userCount": 1000,
      "userPercentage": 20.0,
      "recommendation": "可考虑使用B组或C组策略，D组策略效果有限"
    }
  }
}
```

### 9.7 实验决策

#### 基于贝叶斯分析的决策

**结论**：D组价格高于基准的概率达到98%，超过95%阈值，**可以提前终止实验**。

**决策**：
1. **立即停止实验**
2. **全量上线D组策略**（组合策略：信任要素 + 价格锚定）

#### 基于HTE分析的个性化策略

**结论**：不同用户群体对价格提升策略的反应存在显著差异。

**决策**：
1. **高敏感群体（25-35岁高信用用户，40%）**：使用D组策略（预期提升400元）
2. **中敏感群体（35-45岁或中信用用户，40%）**：使用D组策略（预期提升300元）
3. **低敏感群体（45岁以上或低信用用户，20%）**：使用B组或C组策略（预期提升200-250元）

### 9.8 实验结果总结

#### 核心指标对比

| 组 | 策略 | 平均成交价 | 价格提升 | 成交率 | 价格/市场价 |
|---|---|---|---|---|---|
| A（基准） | 当前版本 | 4500元 | - | 10% | 75% |
| B（变体1） | 信任要素 | 4650元 | +3.3% | 11% | 77.5% |
| C（变体2） | 价格锚定 | 4725元 | +5.0% | 10.5% | 78.75% |
| **D（变体3）** | **组合策略** | **4800元** | **+6.7%** | **12%** | **80%** |

#### 关键发现

1. **组合策略效果最佳**：
   - 价格提升6.7%，达到目标（从75%提升至80%）
   - 成交率不降反升（从10%提升至12%）
   - 击败基准的概率达到98%

2. **信任要素和价格锚定都有价值**：
   - 单独使用信任要素：价格提升3.3%
   - 单独使用价格锚定：价格提升5.0%
   - 组合使用：价格提升6.7%（1+1>2的效果）

3. **个性化策略更优**：
   - 高敏感群体（25-35岁高信用用户）：价格提升可达400元
   - 低敏感群体（45岁以上低信用用户）：价格提升200元
   - 建议采用差异化策略

#### 业务价值

- **价格提升6.7%**：从市场价的75%提升至80%
- **成交率提升**：从10%提升至12%（提升20%）
- **平台收益提升**：假设平台佣金5%，每笔交易收益从225元提升至240元（提升6.7%）
- **卖家收益提升**：每笔交易卖家多收益300元

#### 后续行动

1. **全量上线D组策略**（组合策略：信任要素 + 价格锚定）
2. **实施个性化策略**：根据用户特征推送不同的优化策略
3. **持续监控**：上线后持续监控价格和成交率变化
4. **迭代优化**：
   - 测试更多信任要素（如"7天无理由退货"、"1年质保"）
   - 测试不同的价格锚定方式（如"原价"、"市场价"、"其他平台价"）
   - 测试稀缺性暗示（如"仅剩1台"、"限时优惠"）

### 9.9 监控API

```bash
# 基础统计
GET /api/analysis/experiment/${EXPERIMENT_ID}/statistics

# 贝叶斯分析
GET /api/analysis/experiment/${EXPERIMENT_ID}/bayesian

# 判断是否提前终止
GET /api/analysis/experiment/${EXPERIMENT_ID}/early-stop?variantGroupId=D&baselineGroupId=A

# MAB统计信息
GET /api/traffic/experiment/${EXPERIMENT_ID}/mab/beta?groupId=D
GET /api/traffic/experiment/${EXPERIMENT_ID}/mab/stats?groupId=D
```

---

## 十、故障排查

### 10.1 常见问题

#### 问题1：无法获取实验组

**解决方案**：
```java
String groupId = experimentService.getVisitorGroup(visitorId);
if (groupId == null) {
    groupId = "A";  // 使用默认组
    log.warn("无法获取实验组，使用默认组: visitorId={}", visitorId);
}
```

#### 问题2：visitorId管理

**建议**：
- 使用Cookie存储visitorId（有效期30天）
- 或使用localStorage（前端）
- 确保同一访客使用相同的visitorId

---

**按照本指南，您可以完整地将Pisces A/B测试系统集成到您的项目中，无需用户系统即可使用！**
