# Pisces A/B测试 SDK - 使用指南

## 概述

Pisces SDK是一个独立的、无需用户认证的A/B测试SDK，可以直接集成到您的项目中。

**核心特点**：
- ✅ **无需用户系统**：使用visitorId（访客唯一标识）即可，可以是设备ID、会话ID等
- ✅ **无需Token认证**：所有查询和上报接口都无需认证
- ✅ **简单易用**：只需3个API调用即可完成集成
- ✅ **自动流量分配**：支持MAB算法自动优化流量分配

## JavaScript SDK

### 安装

```html
<!-- 方式1：直接引入 -->
<script src="https://cdn.yourdomain.com/pisces-sdk.js"></script>

<!-- 方式2：npm安装 -->
npm install pisces-sdk
```

### 快速开始

```javascript
// 1. 初始化SDK
const pisces = new PiscesSDK({
  apiBaseUrl: 'http://localhost:8080/api',
  experimentId: 'exp_price_001',
  visitorId: getVisitorId()  // 获取或生成访客ID
});

// 2. 获取实验组
const groupId = await pisces.getGroup();
console.log('访客所在组:', groupId);

// 3. 获取组配置
const config = await pisces.getGroupConfig();
console.log('组配置:', config);

// 4. 上报浏览事件
await pisces.reportView({
  productId: 'iphone_001',
  productPrice: 4500,
  marketPrice: 6000,
  productModel: 'iPhone 13 Pro',
  condition: '95新'
});

// 5. 交易完成时上报（关键指标）
await pisces.reportTransaction({
  transactionId: 'txn_001',
  productId: 'iphone_001',
  transactionPrice: 4800,  // 实际成交价格（核心指标）
  listPrice: 4500,
  marketPrice: 6000
});
```

### 访客ID管理

```javascript
// 生成或获取访客ID
function getVisitorId() {
  // 方式1：从localStorage获取
  let visitorId = localStorage.getItem('pisces_visitor_id');
  if (!visitorId) {
    // 方式2：生成新的访客ID
    visitorId = 'visitor_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    localStorage.setItem('pisces_visitor_id', visitorId);
  }
  return visitorId;
}

// 或使用Cookie
function getVisitorIdFromCookie() {
  const cookies = document.cookie.split(';');
  for (let cookie of cookies) {
    const [name, value] = cookie.trim().split('=');
    if (name === 'pisces_visitor_id') {
      return value;
    }
  }
  // 生成新的
  const visitorId = 'visitor_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
  document.cookie = `pisces_visitor_id=${visitorId}; max-age=2592000; path=/`;  // 30天
  return visitorId;
}
```

## Java SDK

### Maven依赖

```xml
<dependency>
    <groupId>com.pisces</groupId>
    <artifactId>pisces-sdk-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 使用示例

```java
import com.pisces.sdk.PiscesClient;
import java.util.HashMap;
import java.util.Map;

// 1. 初始化客户端
PiscesClient client = new PiscesClient("http://localhost:8080/api");
String experimentId = "exp_price_001";

// 2. 获取访客ID（从Cookie、Session等获取）
String visitorId = getVisitorId(request);

// 3. 分配访客到实验组
String groupId = client.assignGroup(experimentId, visitorId);
System.out.println("访客所在组: " + groupId);

// 4. 获取实验配置
ExperimentConfig experiment = client.getExperiment(experimentId);
ExperimentConfig.GroupConfig groupConfig = experiment.getGroups().get(groupId);
System.out.println("组配置: " + groupConfig.getConfig());

// 5. 上报浏览事件
Map<String, Object> productData = new HashMap<>();
productData.put("productId", "iphone_001");
productData.put("productPrice", 4500.0);
productData.put("marketPrice", 6000.0);
productData.put("productModel", "iPhone 13 Pro");
productData.put("condition", "95新");
client.reportView(experimentId, visitorId, productData);

// 6. 交易完成时上报（关键指标）
Map<String, Object> transactionData = new HashMap<>();
transactionData.put("transactionId", "txn_001");
transactionData.put("productId", "iphone_001");
transactionData.put("transactionPrice", 4800.0);  // 实际成交价格（核心指标）
transactionData.put("listPrice", 4500.0);
transactionData.put("marketPrice", 6000.0);
client.reportTransaction(experimentId, visitorId, transactionData);
```

### Spring Boot集成

```java
@Service
public class ExperimentService {
    
    private final PiscesClient piscesClient;
    private final String experimentId = "exp_price_001";
    
    public ExperimentService() {
        this.piscesClient = new PiscesClient("http://localhost:8080/api");
    }
    
    /**
     * 获取访客实验组
     */
    public String getGroup(String visitorId) {
        return piscesClient.assignGroup(experimentId, visitorId);
    }
    
    /**
     * 上报浏览事件
     */
    public void reportView(String visitorId, Map<String, Object> productData) {
        piscesClient.reportView(experimentId, visitorId, productData);
    }
    
    /**
     * 上报成交事件
     */
    public void reportTransaction(String visitorId, Map<String, Object> transactionData) {
        piscesClient.reportTransaction(experimentId, visitorId, transactionData);
    }
}
```

## API接口说明

### 1. 分配访客到实验组

```http
POST /api/traffic/assign
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345"
}
```

**响应**：
```json
{
  "code": 200,
  "data": "D"
}
```

### 2. 上报事件

```http
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "CONVERT",
  "eventName": "transaction_completed",
  "properties": {
    "transactionPrice": 4800,
    "marketPrice": 6000
  }
}
```

### 3. 获取实验配置

```http
GET /api/experiments/{experimentId}
```

**响应**：
```json
{
  "code": 200,
  "data": {
    "id": "exp_price_001",
    "groups": {
      "D": {
        "config": {
          "showMarketPrice": true,
          "showQualityReport": true
        }
      }
    }
  }
}
```

## 完整集成示例

### 前端完整示例

```html
<!DOCTYPE html>
<html>
<head>
  <script src="pisces-sdk.js"></script>
</head>
<body>
  <div id="product"></div>
  
  <script>
    // 获取访客ID
    function getVisitorId() {
      let id = localStorage.getItem('pisces_visitor_id');
      if (!id) {
        id = 'visitor_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        localStorage.setItem('pisces_visitor_id', id);
      }
      return id;
    }
    
    // 初始化SDK
    const pisces = new PiscesSDK({
      apiBaseUrl: 'http://localhost:8080/api',
      experimentId: 'exp_price_001',
      visitorId: getVisitorId()
    });
    
    // 加载商品
    async function loadProduct(productId) {
      // 获取实验组
      const groupId = await pisces.getGroup();
      
      // 获取组配置
      const config = await pisces.getGroupConfig();
      
      // 根据配置渲染页面
      renderProduct(productId, groupId, config);
      
      // 上报浏览
      await pisces.reportView({
        productId: productId,
        productPrice: 4500,
        marketPrice: 6000
      });
    }
    
    // 交易完成
    async function onTransactionComplete(transaction) {
      await pisces.reportTransaction({
        transactionId: transaction.id,
        productId: transaction.productId,
        transactionPrice: transaction.price,  // 核心指标
        listPrice: transaction.listPrice,
        marketPrice: transaction.marketPrice
      });
    }
  </script>
</body>
</html>
```

---

**使用Pisces SDK，3步即可完成A/B测试集成！**
