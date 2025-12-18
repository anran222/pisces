# Pisces JavaScript SDK

## 安装

### 方式1：直接引入

```html
<script src="https://cdn.yourdomain.com/pisces-sdk.js"></script>
```

### 方式2：npm安装

```bash
npm install pisces-sdk
```

```javascript
import PiscesSDK from 'pisces-sdk';
```

## 快速开始

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

## API文档

### 构造函数

```javascript
new PiscesSDK(config)
```

**参数**：
- `apiBaseUrl` (string): API基础URL，默认 'http://localhost:8080/api'
- `experimentId` (string): 实验ID
- `visitorId` (string): 访客唯一标识

### 方法

#### getGroup()

获取访客所在的实验组。

**返回**：`Promise<string>` - 实验组ID

#### getExperimentConfig()

获取实验配置。

**返回**：`Promise<object>` - 实验配置对象

#### getGroupConfig()

获取当前组的配置。

**返回**：`Promise<object>` - 组配置对象

#### reportEvent(eventType, eventName, properties)

上报事件。

**参数**：
- `eventType` (string): 事件类型 (VIEW, CLICK, CONVERT)
- `eventName` (string): 事件名称
- `properties` (object): 事件属性

#### reportView(productData)

上报浏览事件。

**参数**：
- `productData` (object): 商品数据
  - `productId` (string): 商品ID
  - `productPrice` (number): 商品价格
  - `marketPrice` (number): 市场参考价
  - `productModel` (string): 商品型号
  - `condition` (string): 成色

#### reportClick(clickData)

上报咨询事件。

**参数**：
- `clickData` (object): 点击数据
  - `productId` (string): 商品ID
  - `productPrice` (number): 商品价格

#### reportTransaction(transactionData)

上报成交事件（关键指标）。

**参数**：
- `transactionData` (object): 交易数据
  - `transactionId` (string): 交易ID
  - `productId` (string): 商品ID
  - `transactionPrice` (number): **实际成交价格（核心指标）**
  - `listPrice` (number): 卖家报价
  - `marketPrice` (number): 市场参考价

## 访客ID管理

### 生成访客ID

```javascript
function getVisitorId() {
  // 方式1：从localStorage获取
  let visitorId = localStorage.getItem('pisces_visitor_id');
  if (!visitorId) {
    // 生成新的访客ID
    visitorId = 'visitor_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    localStorage.setItem('pisces_visitor_id', visitorId);
  }
  return visitorId;
}
```

### 使用Cookie

```javascript
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

## 完整示例

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
        marketPrice: 6000,
        productModel: 'iPhone 13 Pro',
        condition: '95新'
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

**更多信息请查看 [完整实施指南](COMPLETE_GUIDE.md)**
