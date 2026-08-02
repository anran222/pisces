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

  static DEFAULT_EXPERIMENT_CACHE_TTL = 60000;

  static DEFAULT_CONFIG_VERSION_LONG_POLL_MILLIS = 0;

  static DEFAULT_MAX_RETRIES = 0;

  static DEFAULT_RETRY_INITIAL_BACKOFF_MILLIS = 100;

  static DEFAULT_RETRY_MAX_BACKOFF_MILLIS = 1000;

  static DEFAULT_RETRY_BACKOFF_JITTER_RATIO = 0.2;

  static HTTP_STATUS_REQUEST_TIMEOUT = 408;

  static HTTP_STATUS_TOO_MANY_REQUESTS = 429;

  static HTTP_STATUS_SERVER_ERROR_MIN = 500;

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
    this.experimentCacheTtl = PiscesSDK.normalizeExperimentCacheTtl(config.experimentCacheTtl);
    this.allowStaleExperimentConfig = config.allowStaleExperimentConfig === true;
    this.configVersionLongPollMillis = PiscesSDK.normalizeConfigVersionLongPollMillis(
      config.configVersionLongPollMillis
    );
    this.maxRetries = PiscesSDK.normalizeMaxRetries(config.maxRetries);
    this.retryInitialBackoffMillis = PiscesSDK.normalizeRetryInitialBackoffMillis(
      config.retryInitialBackoffMillis
    );
    this.retryMaxBackoffMillis = PiscesSDK.normalizeRetryMaxBackoffMillis(
      config.retryMaxBackoffMillis,
      this.retryInitialBackoffMillis
    );
    this.retryBackoffJitterRatio = PiscesSDK.normalizeRetryBackoffJitterRatio(
      config.retryBackoffJitterRatio
    );
    this.now = typeof config.now === 'function' ? config.now : () => Date.now();
    if (typeof this.fetchImpl !== 'function') {
      throw new PiscesSdkError('Pisces SDK fetch实现不能为空');
    }

    this.groupIdCache = null;
    this.assignmentTraceCache = null;
    this.experimentCache = null;
    this.experimentCacheExpiresAt = 0;
    this.metrics = PiscesSDK.createEmptyMetrics();
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

  async assignGroupWithTrace(attributes = {}) {
    if (this.assignmentTraceCache) {
      return this.assignmentTraceCache;
    }
    const assignment = await this.request('/traffic/assign/trace', {
      method: 'POST',
      body: {
        experimentId: this.experimentId,
        visitorId: this.visitorId,
        attributes
      }
    });
    this.assignmentTraceCache = assignment;
    this.groupIdCache = assignment?.groupId ?? null;
    return assignment;
  }

  async getExperiment() {
    if (this.isExperimentCacheFresh()) {
      this.metrics.experimentCacheHitCount += 1;
      return this.experimentCache;
    }
    this.metrics.experimentCacheMissCount += 1;
    if (this.canReuseExpiredExperimentCache()) {
      try {
        const configVersion = await this.getExperimentConfigVersion(this.experimentCache.configVersion);
        if (configVersion && configVersion.changed !== true) {
          this.cacheExperiment(this.experimentCache);
          return this.experimentCache;
        }
      } catch (error) {
        if (this.allowStaleExperimentConfig) {
          this.metrics.staleExperimentConfigFallbackCount += 1;
          return this.experimentCache;
        }
        throw error;
      }
    }
    try {
      const experiment = await this.request(this.runtimeConfigPath(), {
        method: 'GET'
      });
      this.cacheExperiment(experiment);
      return experiment;
    } catch (error) {
      if (this.allowStaleExperimentConfig && this.experimentCache) {
        this.metrics.staleExperimentConfigFallbackCount += 1;
        return this.experimentCache;
      }
      throw error;
    }
  }

  async getGroupConfig(attributes = {}) {
    const assignment = await this.assignGroupWithTrace(attributes);
    let experimentResolution = await this.resolveExperimentForAssignment(assignment);
    let experiment = experimentResolution.experiment;
    let effectiveAssignment = assignment;
    if (!experimentResolution.staleFallback && this.shouldRefreshAssignmentForExperiment(assignment, experiment)) {
      this.clearAssignmentCache();
      effectiveAssignment = await this.assignGroupWithTrace(attributes);
      experimentResolution = await this.resolveExperimentForAssignment(effectiveAssignment);
      experiment = experimentResolution.experiment;
    }
    const groupId = effectiveAssignment?.groupId ?? null;
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
    this.assignmentTraceCache = null;
    this.experimentCache = null;
    this.experimentCacheExpiresAt = 0;
  }

  clearExperimentCache() {
    this.experimentCache = null;
    this.experimentCacheExpiresAt = 0;
  }

  clearAssignmentCache() {
    this.groupIdCache = null;
    this.assignmentTraceCache = null;
  }

  async getExperimentForAssignment(assignment) {
    const resolution = await this.resolveExperimentForAssignment(assignment);
    return resolution.experiment;
  }

  async resolveExperimentForAssignment(assignment) {
    const experiment = await this.getExperiment();
    if (!assignment?.configVersion
      || !experiment?.configVersion
      || assignment.configVersion === experiment.configVersion) {
      return {
        experiment,
        staleFallback: false
      };
    }
    const staleExperiment = experiment;
    this.clearExperimentCache();
    try {
      return {
        experiment: await this.getExperiment(),
        staleFallback: false
      };
    } catch (error) {
      if (this.canUseStaleExperimentConfigForAssignment(staleExperiment, assignment)) {
        this.cacheExperiment(staleExperiment);
        this.metrics.staleExperimentConfigFallbackCount += 1;
        return {
          experiment: staleExperiment,
          staleFallback: true
        };
      }
      throw error;
    }
  }

  canUseStaleExperimentConfigForAssignment(experiment, assignment) {
    return this.allowStaleExperimentConfig
      && experiment
      && assignment?.groupId
      && experiment.groups?.[assignment.groupId];
  }

  shouldRefreshAssignmentForExperiment(assignment, experiment) {
    return assignment?.configVersion
      && experiment?.configVersion
      && assignment.configVersion !== experiment.configVersion;
  }

  isExperimentCacheFresh() {
    return this.experimentCache
      && this.experimentCacheTtl > 0
      && this.experimentCacheExpiresAt > this.now();
  }

  cacheExperiment(experiment) {
    if (this.experimentCacheTtl <= 0 || !experiment) {
      return;
    }
    this.experimentCache = experiment;
    this.experimentCacheExpiresAt = this.now() + this.experimentCacheTtl;
  }

  canReuseExpiredExperimentCache() {
    return this.experimentCache
      && this.experimentCacheTtl > 0
      && this.experimentCache.configVersion != null;
  }

  runtimeConfigPath() {
    return `/runtime/experiments/${this.experimentId}/config`;
  }

  runtimeConfigVersionPath(knownVersion) {
    const path = `${this.runtimeConfigPath()}/version`;
    const query = new URLSearchParams();
    if (knownVersion != null) {
      query.set('knownVersion', knownVersion);
    }
    if (this.configVersionLongPollMillis > 0) {
      query.set('waitMillis', this.configVersionLongPollMillis);
    }
    const queryText = query.toString();
    return queryText ? `${path}?${queryText}` : path;
  }

  async getExperimentConfigVersion(knownVersion) {
    this.metrics.experimentVersionCheckCount += 1;
    return this.request(this.runtimeConfigVersionPath(knownVersion), {
      method: 'GET'
    });
  }

  async request(path, options) {
    let lastError = null;
    for (let attemptIndex = 0; attemptIndex <= this.maxRetries; attemptIndex += 1) {
      if (attemptIndex > 0) {
        this.metrics.retryCount += 1;
        await this.sleepBeforeRetry(attemptIndex);
      }
      this.metrics.requestAttemptCount += 1;
      try {
        const response = await this.requestOnce(path, options);
        this.metrics.requestSuccessCount += 1;
        return response;
      } catch (error) {
        const sdkError = this.normalizeError(error, path);
        this.metrics.requestFailureCount += 1;
        lastError = sdkError;
        if (this.onError) {
          this.onError(sdkError);
        }
        if (!this.shouldRetry(sdkError, attemptIndex)) {
          throw sdkError;
        }
      }
    }
    throw lastError;
  }

  async requestOnce(path, options) {
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
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    }
  }

  getMetricsSnapshot() {
    return { ...this.metrics };
  }

  resetMetrics() {
    this.metrics = PiscesSDK.createEmptyMetrics();
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

  shouldRetry(error, attemptIndex) {
    return attemptIndex < this.maxRetries && this.isRetryable(error);
  }

  isRetryable(error) {
    if (!error || error.code === 'INTERRUPTED') {
      return false;
    }
    if (error.code === 'REQUEST_ERROR'
      || error.code === 'TIMEOUT'
      || error.code === 'EMPTY_RESPONSE') {
      return true;
    }
    if (error.code === 'HTTP_ERROR') {
      return error.httpStatus === PiscesSDK.HTTP_STATUS_REQUEST_TIMEOUT
        || error.httpStatus === PiscesSDK.HTTP_STATUS_TOO_MANY_REQUESTS
        || error.httpStatus >= PiscesSDK.HTTP_STATUS_SERVER_ERROR_MIN;
    }
    const numericCode = Number.parseInt(error.code, 10);
    return Number.isFinite(numericCode)
      && (numericCode === PiscesSDK.HTTP_STATUS_REQUEST_TIMEOUT
        || numericCode === PiscesSDK.HTTP_STATUS_TOO_MANY_REQUESTS
        || numericCode >= PiscesSDK.HTTP_STATUS_SERVER_ERROR_MIN);
  }

  async sleepBeforeRetry(retryNumber) {
    const delayMillis = this.calculateRetryDelayMillis(retryNumber);
    if (delayMillis <= 0) {
      return;
    }
    await new Promise((resolve) => {
      setTimeout(resolve, delayMillis);
    });
  }

  calculateRetryDelayMillis(retryNumber) {
    let delayMillis = this.retryInitialBackoffMillis;
    for (let index = 1; index < retryNumber; index += 1) {
      if (delayMillis >= this.retryMaxBackoffMillis / 2) {
        delayMillis = this.retryMaxBackoffMillis;
        break;
      }
      delayMillis *= 2;
    }
    delayMillis = Math.min(delayMillis, this.retryMaxBackoffMillis);
    if (delayMillis <= 0 || this.retryBackoffJitterRatio <= 0) {
      return delayMillis;
    }
    const jitterMillis = Math.round(delayMillis * this.retryBackoffJitterRatio);
    const minDelayMillis = Math.max(0, delayMillis - jitterMillis);
    const maxDelayMillis = delayMillis + jitterMillis;
    return Math.floor(Math.random() * (maxDelayMillis - minDelayMillis + 1)) + minDelayMillis;
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

  static normalizeExperimentCacheTtl(experimentCacheTtl) {
    if (experimentCacheTtl == null) {
      return PiscesSDK.DEFAULT_EXPERIMENT_CACHE_TTL;
    }
    if (!Number.isFinite(experimentCacheTtl) || experimentCacheTtl < 0) {
      throw new PiscesSdkError('Pisces SDK experimentCacheTtl不能小于0');
    }
    return experimentCacheTtl;
  }

  static normalizeConfigVersionLongPollMillis(configVersionLongPollMillis) {
    if (configVersionLongPollMillis == null) {
      return PiscesSDK.DEFAULT_CONFIG_VERSION_LONG_POLL_MILLIS;
    }
    if (!Number.isFinite(configVersionLongPollMillis) || configVersionLongPollMillis < 0) {
      throw new PiscesSdkError('Pisces SDK configVersionLongPollMillis不能小于0');
    }
    return configVersionLongPollMillis;
  }

  static normalizeMaxRetries(maxRetries) {
    if (maxRetries == null) {
      return PiscesSDK.DEFAULT_MAX_RETRIES;
    }
    if (!Number.isInteger(maxRetries) || maxRetries < 0) {
      throw new PiscesSdkError('Pisces SDK maxRetries不能小于0');
    }
    return maxRetries;
  }

  static normalizeRetryInitialBackoffMillis(retryInitialBackoffMillis) {
    if (retryInitialBackoffMillis == null) {
      return PiscesSDK.DEFAULT_RETRY_INITIAL_BACKOFF_MILLIS;
    }
    if (!Number.isFinite(retryInitialBackoffMillis) || retryInitialBackoffMillis < 0) {
      throw new PiscesSdkError('Pisces SDK retryInitialBackoffMillis不能小于0');
    }
    return retryInitialBackoffMillis;
  }

  static normalizeRetryMaxBackoffMillis(retryMaxBackoffMillis, retryInitialBackoffMillis) {
    if (retryMaxBackoffMillis == null) {
      return PiscesSDK.DEFAULT_RETRY_MAX_BACKOFF_MILLIS;
    }
    if (!Number.isFinite(retryMaxBackoffMillis) || retryMaxBackoffMillis < retryInitialBackoffMillis) {
      throw new PiscesSdkError('Pisces SDK retryMaxBackoffMillis不能小于初始退避时间');
    }
    return retryMaxBackoffMillis;
  }

  static normalizeRetryBackoffJitterRatio(retryBackoffJitterRatio) {
    if (retryBackoffJitterRatio == null) {
      return PiscesSDK.DEFAULT_RETRY_BACKOFF_JITTER_RATIO;
    }
    if (!Number.isFinite(retryBackoffJitterRatio)
      || retryBackoffJitterRatio < 0
      || retryBackoffJitterRatio > 1) {
      throw new PiscesSdkError('Pisces SDK retryBackoffJitterRatio必须在0到1之间');
    }
    return retryBackoffJitterRatio;
  }

  static createEmptyMetrics() {
    return {
      requestAttemptCount: 0,
      requestSuccessCount: 0,
      requestFailureCount: 0,
      retryCount: 0,
      staleExperimentConfigFallbackCount: 0,
      experimentCacheHitCount: 0,
      experimentCacheMissCount: 0,
      experimentVersionCheckCount: 0
    };
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
