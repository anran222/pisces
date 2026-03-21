class PiscesSdkError extends Error {
  constructor(message, options = {}) {
    super(message);
    this.name = 'PiscesSdkError';
    this.code = options.code ?? null;
    this.httpStatus = options.httpStatus ?? null;
    this.requestPath = options.requestPath ?? null;
    this.details = options.details ?? null;
  }
}

class PiscesSDK {
  static SUCCESS_CODE = 200;

  static DEFAULT_TIMEOUT = 30000;

  static COMPAT_VIEW_EVENT_TYPE = 'VIEW';

  static COMPAT_VIEW_EVENT_NAME = 'product_view';

  static COMPAT_CLICK_EVENT_TYPE = 'CLICK';

  static COMPAT_CLICK_EVENT_NAME = 'contact_seller';

  static COMPAT_CONVERT_EVENT_TYPE = 'CONVERT';

  static COMPAT_CONVERT_EVENT_NAME = 'transaction_completed';

  constructor(config = {}) {
    this.apiBaseUrl = PiscesSDK.normalizeBaseUrl(config.apiBaseUrl);
    this.experimentId = PiscesSDK.requireText(config.experimentId, 'Pisces SDK experimentId不能为空');
    this.visitorId = PiscesSDK.requireText(config.visitorId, 'Pisces SDK visitorId不能为空');
    this.timeout = PiscesSDK.normalizeTimeout(config.timeout);
    this.headers = { ...(config.headers || {}) };
    this.onError = typeof config.onError === 'function' ? config.onError : null;
    this.fetchImpl = config.fetchImpl || globalThis.fetch;
    if (typeof this.fetchImpl !== 'function') {
      throw new PiscesSdkError('Pisces SDK fetch实现不能为空');
    }

    this.groupIdCache = null;
    this.experimentCache = null;
  }

  async assignGroup(attributes = {}) {
    if (this.groupIdCache) {
      return this.groupIdCache;
    }
    const groupId = await this.request('/traffic/assign', {
      method: 'POST',
      body: {
        experimentId: this.experimentId,
        visitorId: this.visitorId,
        attributes
      }
    });
    this.groupIdCache = groupId;
    return groupId;
  }

  async getExperiment() {
    if (this.experimentCache) {
      return this.experimentCache;
    }
    const experiment = await this.request(`/experiments/${this.experimentId}`, {
      method: 'GET'
    });
    this.experimentCache = experiment;
    return experiment;
  }

  async getGroupConfig(attributes = {}) {
    const [groupId, experiment] = await Promise.all([
      this.assignGroup(attributes),
      this.getExperiment()
    ]);
    return experiment?.groups?.[groupId]?.config ?? null;
  }

  async getGroupConfigSchema() {
    const experiment = await this.getExperiment()
    return experiment?.groupConfigSchema ?? []
  }

  async getEventDefinitions() {
    const experiment = await this.getExperiment()
    return experiment?.eventDefinitions ?? []
  }

  async getMetricDefinitions() {
    const experiment = await this.getExperiment()
    return experiment?.metricDefinitions ?? []
  }

  async reportExposure(properties = {}) {
    await this.request('/data/exposure', {
      method: 'POST',
      body: {
        experimentId: this.experimentId,
        visitorId: this.visitorId,
        properties
      }
    });
  }

  async reportEvent(eventType, eventName, properties = {}) {
    await this.request('/data/event', {
      method: 'POST',
      body: {
        experimentId: this.experimentId,
        visitorId: this.visitorId,
        eventType: PiscesSDK.requireText(eventType, 'Pisces SDK eventType不能为空'),
        eventName: PiscesSDK.requireText(eventName, 'Pisces SDK eventName不能为空'),
        properties
      }
    });
  }

  async reportEventByKey(eventKey, properties = {}) {
    const normalizedEventKey = PiscesSDK.requireText(eventKey, 'Pisces SDK eventKey不能为空');
    await this.reportEvent(normalizedEventKey, normalizedEventKey, properties);
  }

  async reportView(properties = {}) {
    await this.reportEvent(PiscesSDK.COMPAT_VIEW_EVENT_TYPE, PiscesSDK.COMPAT_VIEW_EVENT_NAME, properties);
  }

  async reportClick(properties = {}) {
    await this.reportEvent(PiscesSDK.COMPAT_CLICK_EVENT_TYPE, PiscesSDK.COMPAT_CLICK_EVENT_NAME, properties);
  }

  async reportConvert(properties = {}) {
    await this.reportEvent(PiscesSDK.COMPAT_CONVERT_EVENT_TYPE, PiscesSDK.COMPAT_CONVERT_EVENT_NAME, properties);
  }

  clearCache() {
    this.groupIdCache = null;
    this.experimentCache = null;
  }

  async request(path, options) {
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timeoutId = controller
      ? setTimeout(() => controller.abort(), this.timeout)
      : null;
    try {
      const response = await this.fetchImpl(`${this.apiBaseUrl}${path}`, {
        method: options.method,
        headers: this.buildHeaders(options.method),
        body: options.body ? JSON.stringify(options.body) : undefined,
        signal: controller?.signal
      });
      return await this.unwrapResponse(path, response);
    } catch (error) {
      const sdkError = this.normalizeError(error, path);
      if (this.onError) {
        this.onError(sdkError);
      }
      throw sdkError;
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    }
  }

  buildHeaders(method) {
    const headers = { ...this.headers };
    if (method !== 'GET') {
      headers['Content-Type'] = 'application/json';
    }
    return headers;
  }

  async unwrapResponse(path, response) {
    const rawBody = await response.text();
    if (!response.ok) {
      throw new PiscesSdkError('Pisces SDK请求失败', {
        code: 'HTTP_ERROR',
        httpStatus: response.status,
        requestPath: path,
        details: rawBody
      });
    }
    if (!rawBody) {
      throw new PiscesSdkError('Pisces SDK响应体为空', {
        code: 'EMPTY_RESPONSE',
        httpStatus: response.status,
        requestPath: path
      });
    }

    let payload;
    try {
      payload = JSON.parse(rawBody);
    } catch (error) {
      throw new PiscesSdkError('Pisces SDK响应解析失败', {
        code: 'INVALID_RESPONSE',
        httpStatus: response.status,
        requestPath: path,
        details: rawBody
      });
    }

    if (payload.code !== PiscesSDK.SUCCESS_CODE) {
      throw new PiscesSdkError(payload.message || 'Pisces SDK业务请求失败', {
        code: String(payload.code),
        httpStatus: response.status,
        requestPath: path,
        details: payload
      });
    }

    return payload.data ?? null;
  }

  normalizeError(error, path) {
    if (error instanceof PiscesSdkError) {
      return error;
    }
    if (error?.name === 'AbortError') {
      return new PiscesSdkError('Pisces SDK请求超时', {
        code: 'TIMEOUT',
        requestPath: path
      });
    }
    return new PiscesSdkError(error?.message || 'Pisces SDK请求失败', {
      code: 'REQUEST_ERROR',
      requestPath: path,
      details: error
    });
  }

  static normalizeBaseUrl(baseUrl) {
    const normalized = PiscesSDK.requireText(baseUrl, 'Pisces SDK apiBaseUrl不能为空');
    return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
  }

  static normalizeTimeout(timeout) {
    if (timeout == null) {
      return PiscesSDK.DEFAULT_TIMEOUT;
    }
    if (!Number.isFinite(timeout) || timeout <= 0) {
      throw new PiscesSdkError('Pisces SDK timeout必须大于0');
    }
    return timeout;
  }

  static requireText(value, message) {
    if (typeof value !== 'string' || !value.trim()) {
      throw new PiscesSdkError(message);
    }
    return value.trim();
  }

  static getOrCreateVisitorId(storageKey = 'pisces_visitor_id') {
    const storage = PiscesSDK.resolveStorage();
    if (storage) {
      const existing = storage.getItem(storageKey);
      if (existing) {
        return existing;
      }
    }
    const visitorId = PiscesSDK.generateVisitorId();
    if (storage) {
      storage.setItem(storageKey, visitorId);
    }
    return visitorId;
  }

  static resolveStorage() {
    try {
      if (typeof globalThis.localStorage !== 'undefined') {
        return globalThis.localStorage;
      }
    } catch (error) {
      return null;
    }
    return null;
  }

  static generateVisitorId() {
    if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
      return `visitor_${globalThis.crypto.randomUUID()}`;
    }
    return `visitor_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  }
}

PiscesSDK.PiscesSdkError = PiscesSdkError;

if (typeof module !== 'undefined' && module.exports) {
  module.exports = PiscesSDK;
  module.exports.PiscesSDK = PiscesSDK;
  module.exports.PiscesSdkError = PiscesSdkError;
}

if (typeof window !== 'undefined') {
  window.PiscesSDK = PiscesSDK;
  window.PiscesSdkError = PiscesSdkError;
}
