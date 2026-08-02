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

test('should assign group with trace and cache result', async () => {
  const calls = [];
  const traceResponse = {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    canonicalVisitorId: 'visitor_001',
    groupId: 'group_a',
    assigned: true,
    reason: 'ALLOCATED',
    source: 'NEW_ASSIGNMENT',
    strategy: 'HASH',
    configVersion: 2
  };
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return createJsonResponse(200, { code: 200, message: 'ok', data: traceResponse });
    }
  });

  const first = await sdk.assignGroupWithTrace({ city: 'shanghai' });
  const second = await sdk.assignGroupWithTrace({ city: 'beijing' });

  assert.deepEqual(first, traceResponse);
  assert.deepEqual(second, traceResponse);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'http://localhost:9990/api/traffic/assign/trace');
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    attributes: { city: 'shanghai' }
  });
  assert.equal(sdk.groupIdCache, 'group_a');
});

test('should retry transient http failure when configured', async () => {
  const calls = [];
  const responses = [
    createJsonResponse(500, {
      code: 500,
      message: '系统异常',
      data: null
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: 'group_a'
    })
  ];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    maxRetries: 1,
    retryInitialBackoffMillis: 0,
    retryMaxBackoffMillis: 0,
    retryBackoffJitterRatio: 0,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  const groupId = await sdk.assignGroup({ city: 'shanghai' });
  const metrics = sdk.getMetricsSnapshot();

  assert.equal(groupId, 'group_a');
  assert.equal(calls.length, 2);
  assert.equal(metrics.requestAttemptCount, 2);
  assert.equal(metrics.requestSuccessCount, 1);
  assert.equal(metrics.requestFailureCount, 1);
  assert.equal(metrics.retryCount, 1);
});

test('should get experiment and group config', async () => {
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        experimentId: 'exp_001',
        visitorId: 'visitor_001',
        groupId: 'group_b',
        assigned: true,
        reason: 'ALLOCATED',
        source: 'NEW_ASSIGNMENT',
        strategy: 'HASH',
        configVersion: 3
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 3,
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
  assert.equal(calls[0].url, 'http://localhost:9990/api/traffic/assign/trace');
  assert.equal(calls[1].url, 'http://localhost:9990/api/runtime/experiments/exp_001/config');
});

test('should cache experiment config within ttl', async () => {
  let calls = 0;
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    experimentCacheTtl: 60000,
    fetchImpl: async () => {
      calls += 1;
      return createJsonResponse(200, {
        code: 200,
        message: 'ok',
        data: {
          id: 'exp_001',
          name: '价格实验',
          status: 'RUNNING',
          configVersion: 3
        }
      });
    }
  });

  const first = await sdk.getExperiment();
  const second = await sdk.getExperiment();

  assert.equal(first.id, 'exp_001');
  assert.equal(second.id, 'exp_001');
  assert.equal(calls, 1);
  const metrics = sdk.getMetricsSnapshot();
  assert.equal(metrics.experimentCacheHitCount, 1);
  assert.equal(metrics.experimentCacheMissCount, 1);
  assert.equal(metrics.requestAttemptCount, 1);

  sdk.resetMetrics();
  assert.equal(sdk.getMetricsSnapshot().requestAttemptCount, 0);
});

test('should extend experiment cache when expired version is unchanged', async () => {
  let now = 1000;
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 3
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        experimentId: 'exp_001',
        knownVersion: 3,
        currentVersion: 3,
        changed: false,
        status: 'RUNNING'
      }
    })
  ];
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    experimentCacheTtl: 10,
    now: () => now,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  const first = await sdk.getExperiment();
  now = 2000;
  const second = await sdk.getExperiment();

  assert.equal(first.id, 'exp_001');
  assert.equal(second.id, 'exp_001');
  assert.equal(calls.length, 2);
  assert.equal(
    calls[1].url,
    'http://localhost:9990/api/runtime/experiments/exp_001/config/version?knownVersion=3'
  );
});

test('should append config version long poll millis when checking expired cache version', async () => {
  let now = 1000;
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 3
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        experimentId: 'exp_001',
        knownVersion: 3,
        currentVersion: 3,
        changed: false,
        status: 'RUNNING'
      }
    })
  ];
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    experimentCacheTtl: 10,
    configVersionLongPollMillis: 250,
    now: () => now,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  await sdk.getExperiment();
  now = 2000;
  await sdk.getExperiment();

  assert.equal(calls.length, 2);
  assert.equal(
    calls[1].url,
    'http://localhost:9990/api/runtime/experiments/exp_001/config/version?knownVersion=3&waitMillis=250'
  );
});

test('should refresh experiment config when assignment version differs', async () => {
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 1
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        experimentId: 'exp_001',
        visitorId: 'visitor_001',
        groupId: 'group_b',
        assigned: true,
        reason: 'ALLOCATED',
        source: 'NEW_ASSIGNMENT',
        strategy: 'HASH',
        configVersion: 2
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 2,
        groups: {
          group_b: {
            id: 'group_b',
            name: '实验组',
            config: { discount: '20%' }
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
    experimentCacheTtl: 60000,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  await sdk.getExperiment();
  const config = await sdk.getGroupConfig();

  assert.equal(config.discount, '20%');
  assert.equal(calls.length, 3);
  assert.equal(calls[2].url, 'http://localhost:9990/api/runtime/experiments/exp_001/config');
});

test('should use stale group config when assignment version differs and refresh fails', async () => {
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 1,
        groups: {
          group_b: {
            id: 'group_b',
            name: '实验组',
            config: { discount: '15%' }
          }
        }
      }
    }),
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        experimentId: 'exp_001',
        visitorId: 'visitor_001',
        groupId: 'group_b',
        assigned: true,
        reason: 'ALLOCATED',
        source: 'NEW_ASSIGNMENT',
        strategy: 'HASH',
        configVersion: 2
      }
    }),
    createJsonResponse(500, {
      code: 500,
      message: '系统异常',
      data: null
    })
  ];
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    experimentCacheTtl: 60000,
    allowStaleExperimentConfig: true,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  await sdk.getExperiment();
  const config = await sdk.getGroupConfig();

  assert.equal(config.discount, '15%');
  assert.equal(calls.length, 3);
});

test('should return stale experiment config when refresh fails and fallback enabled', async () => {
  let now = 1000;
  const responses = [
    createJsonResponse(200, {
      code: 200,
      message: 'ok',
      data: {
        id: 'exp_001',
        name: '价格实验',
        status: 'RUNNING',
        configVersion: 3
      }
    }),
    createJsonResponse(500, {
      code: 500,
      message: '系统异常',
      data: null
    })
  ];
  const calls = [];
  const sdk = new PiscesSDK({
    apiBaseUrl: 'http://localhost:9990/api',
    experimentId: 'exp_001',
    visitorId: 'visitor_001',
    experimentCacheTtl: 10,
    allowStaleExperimentConfig: true,
    now: () => now,
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return responses.shift();
    }
  });

  const fresh = await sdk.getExperiment();
  now = 2000;
  const stale = await sdk.getExperiment();

  assert.equal(fresh.id, 'exp_001');
  assert.equal(stale.id, 'exp_001');
  assert.equal(calls.length, 2);
  assert.equal(sdk.getMetricsSnapshot().staleExperimentConfigFallbackCount, 1);
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
