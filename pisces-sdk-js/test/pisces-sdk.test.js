const test = require('node:test');
const assert = require('node:assert/strict');

const PiscesSDK = require('../pisces-sdk');
const { PiscesSdkError } = PiscesSDK;

test('should assign group and cache result', async () => {
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return createJsonResponse(200, { code: 200, message: 'ok', data: 'group_a' });
    }
  });

  const first = await sdk.assignGroup({ city: 'shanghai' });
  const second = await sdk.assignGroup({ city: 'beijing' });

  assert.equal(first, 'group_a');
  assert.equal(second, 'group_a');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'http://localhost:9990/api/traffic/assign');
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    attributes: { city: 'shanghai' }
  });
});

test('should get experiment and group config', async () => {
  const responses = [
    createJsonResponse(200, { code: 200, message: 'ok', data: 'group_b' }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        eventDefinitions: [
          {
            key: 'PRODUCT_VIEW',
            label: '商品查看',
            description: '进入商品详情页',
            category: 'FUNNEL',
            primary: true
          }
        ],
        metricDefinitions: [
          {
            key: 'PAYMENT_RATE',
            name: '支付率',
            description: '支付成功占查看比率',
            aggregationType: 'RATE',
            numeratorEventType: 'PAY_SUCCESS',
            denominatorType: 'EVENT_COUNT',
            denominatorEventType: 'PRODUCT_VIEW',
            primaryMetric: true,
            guardrailMetric: false
          }
        ],
        groupConfigSchema: [
          {
            key: 'mainTitle',
            label: '主标题',
            valueType: 'STRING',
            required: true,
            description: '商品卡标题',
            defaultValue: '默认主标题'
          }
        ],
        groups: {
          group_b: {
            id: 'group_b',
            name: '实验组',
            config: { discount: '15%' }
          }
        }
      }
    })
  ];
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  const config = await sdk.getGroupConfig();

  assert.equal(config.discount, '15%');
  assert.equal(calls[0].url, 'http://localhost:9990/api/traffic/assign');
  assert.equal(calls[1].url, 'http://localhost:9990/api/experiments/exp_001');
});

test('should expose group config schema from experiment response', async () => {
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async () => createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        groupConfigSchema: [
          {
            key: 'mainTitle',
            label: '主标题',
            valueType: 'STRING',
            required: true,
            description: '商品卡标题',
            defaultValue: '默认主标题'
          },
          {
            key: 'badgeCount',
            label: '标签数量',
            valueType: 'INTEGER',
            required: false,
            description: '标签数量',
            defaultValue: 2
          }
        ],
        groups: {}
      }
    })
  });

  const schema = await sdk.getGroupConfigSchema();

  assert.equal(schema.length, 2);
  assert.equal(schema[0].key, 'mainTitle');
  assert.equal(schema[1].valueType, 'INTEGER');
});

test('should expose event definitions and metric definitions from experiment response', async () => {
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async () => createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        eventDefinitions: [
          {
            key: 'PRODUCT_VIEW',
            label: '商品查看',
            description: '进入商品详情页',
            category: 'FUNNEL',
            primary: true
          },
          {
            key: 'PAY_SUCCESS',
            label: '支付成功',
            description: '完成支付',
            category: 'RESULT',
            primary: false
          }
        ],
        metricDefinitions: [
          {
            key: 'PAYMENT_RATE',
            name: '支付率',
            description: '支付成功占查看比率',
            aggregationType: 'RATE',
            numeratorEventType: 'PAY_SUCCESS',
            denominatorType: 'EVENT_COUNT',
            denominatorEventType: 'PRODUCT_VIEW',
            primaryMetric: true,
            guardrailMetric: false
          }
        ]
      }
    })
  });

  const eventDefinitions = await sdk.getEventDefinitions();
  const metricDefinitions = await sdk.getMetricDefinitions();

  assert.equal(eventDefinitions.length, 2);
  assert.equal(eventDefinitions[0].key, 'PRODUCT_VIEW');
  assert.equal(metricDefinitions.length, 1);
  assert.equal(metricDefinitions[0].key, 'PAYMENT_RATE');
});

test('should report exposure and convert event', async () => {
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return createJsonResponse(200, { code: 200, message: 'ok', data: null });
    }
  });

  await sdk.reportExposure({ page: 'detail' });
  await sdk.reportConvert({ transactionId: 'txn_001' });

  assert.equal(calls[0].url, 'http://localhost:9990/api/data/exposure');
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    properties: { page: 'detail' }
  });
  assert.equal(calls[1].url, 'http://localhost:9990/api/data/event');
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    eventType: 'CONVERT',
    eventName: 'transaction_completed',
    properties: { transactionId: 'txn_001' }
  });
});

test('should report event by event key', async () => {
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return createJsonResponse(200, { code: 200, message: 'ok', data: null });
    }
  });

  await sdk.reportEventByKey('PAY_SUCCESS', { orderId: 'ord_001' });

  assert.equal(calls[0].url, 'http://localhost:9990/api/data/event');
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    eventType: 'PAY_SUCCESS',
    eventName: 'PAY_SUCCESS',
    properties: { orderId: 'ord_001' }
  });
});

test('should throw PiscesSdkError when business code is not success', async () => {
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async () => createJsonResponse(200, {
      code: 500,
      message: '实验不存在',
      data: null
    })
  });

  await assert.rejects(
    () => sdk.assignGroup(),
    (error) => {
      assert.ok(error instanceof PiscesSdkError);
      assert.equal(error.message, '实验不存在');
      assert.equal(error.code, '500');
      assert.equal(error.requestPath, '/traffic/assign');
      return true;
    }
  );
});

test('should create visitor id from localStorage', () => {
  const store = new Map();
  const originalLocalStorage = globalThis.localStorage;
  globalThis.localStorage = {
    getItem(key) {
      return store.get(key) ?? null;
    },
    setItem(key, value) {
      store.set(key, value);
    }
  };

  const first = PiscesSDK.getOrCreateVisitorId();
  const second = PiscesSDK.getOrCreateVisitorId();

  assert.ok(first.startsWith('visitor_'));
  assert.equal(first, second);
  globalThis.localStorage = originalLocalStorage;
});

function createJsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async text() {
      return JSON.stringify(payload);
    }
  };
}
