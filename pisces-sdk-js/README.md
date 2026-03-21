# Pisces JavaScript SDK

`pisces-sdk-js` 是面向业务前端页面的运行时接入 SDK。

## 覆盖能力

- 分流
- 查询实验详情
- 查询实验级 `eventDefinitions`
- 查询实验级 `metricDefinitions`
- 查询实验级 `groupConfigSchema`
- 查询当前命中组的 `config`
- 上报曝光
- 按实验事件键上报事件

## 快速开始

```javascript
const pisces = new PiscesSDK({
  apiBaseUrl: 'http://localhost:9990/api',
  experimentId: 'exp_price_001',
  visitorId: PiscesSDK.getOrCreateVisitorId()
})

const groupId = await pisces.assignGroup({ city: 'shanghai' })
const experiment = await pisces.getExperiment()
const eventDefinitions = await pisces.getEventDefinitions()
const metricDefinitions = await pisces.getMetricDefinitions()
const schema = await pisces.getGroupConfigSchema()
const groupConfig = await pisces.getGroupConfig()

await pisces.reportExposure({ page: 'detail' })
await pisces.reportEventByKey('PAY_SUCCESS', { orderId: 'ord_001' })
await pisces.reportView({ productId: 'iphone_001' })
await pisces.reportClick({ productId: 'iphone_001' })
await pisces.reportConvert({ transactionId: 'txn_001' })
```

## 核心方法

- `assignGroup(attributes = {})`
- `getExperiment()`
- `getEventDefinitions()`
- `getMetricDefinitions()`
- `getGroupConfigSchema()`
- `getGroupConfig(attributes = {})`
- `reportExposure(properties = {})`
- `reportEvent(eventType, eventName, properties = {})`
- `reportEventByKey(eventKey, properties = {})`
- `reportView(properties = {})`
- `reportClick(properties = {})`
- `reportConvert(properties = {})`
- `clearCache()`
- `PiscesSDK.getOrCreateVisitorId(storageKey = 'pisces_visitor_id')`

## 返回结构

`getExperiment()` 返回结果中当前会包含：

- `eventDefinitions`
- `metricDefinitions`
- `groupConfigSchema`
- `groups`
- `traffic`

## 不包含

- 实验创建和管理
- AI 设计、诊断、毕业
- 变体生成
- 演示实验生成
