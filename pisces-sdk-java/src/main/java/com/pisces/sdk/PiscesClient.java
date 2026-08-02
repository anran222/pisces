package com.pisces.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.sdk.exception.PiscesSdkException;
import com.pisces.sdk.model.BaseResponse;
import com.pisces.sdk.model.EventDefinition;
import com.pisces.sdk.model.EventReportRequest;
import com.pisces.sdk.model.ExperimentConfig;
import com.pisces.sdk.model.ExperimentGroupConfig;
import com.pisces.sdk.model.ExposureReportRequest;
import com.pisces.sdk.model.GroupConfigFieldDefinition;
import com.pisces.sdk.model.MetricDefinition;
import com.pisces.sdk.model.PiscesClientMetricsSnapshot;
import com.pisces.sdk.model.RuntimeConfigVersion;
import com.pisces.sdk.model.TrafficAssignRequest;
import com.pisces.sdk.model.TrafficAssignResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pisces运行时客户端
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public final class PiscesClient {

    private static final String CONTENT_TYPE = "application/json";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final int SUCCESS_CODE = 200;
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    private static final long DEFAULT_EXPERIMENT_CACHE_TTL_MILLIS = 60_000L;
    private static final long DEFAULT_CONFIG_VERSION_LONG_POLL_MILLIS = 0L;
    private static final int DEFAULT_MAX_RETRIES = 0;
    private static final long DEFAULT_RETRY_INITIAL_BACKOFF_MILLIS = 100L;
    private static final long DEFAULT_RETRY_MAX_BACKOFF_MILLIS = 1_000L;
    private static final double DEFAULT_RETRY_BACKOFF_JITTER_RATIO = 0.2D;
    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String CODE_IO_ERROR = "IO_ERROR";
    private static final String CODE_INTERRUPTED = "INTERRUPTED";
    private static final String CODE_EMPTY_RESPONSE = "EMPTY_RESPONSE";
    private static final String CODE_HTTP_ERROR = "HTTP_ERROR";
    private static final String CODE_SERIALIZE_ERROR = "SERIALIZE_ERROR";
    private static final String CODE_INVALID_RESPONSE = "INVALID_RESPONSE";
    private static final String CODE_REQUEST_ERROR = "REQUEST_ERROR";
    private static final String CODE_GROUP_NOT_FOUND = "GROUP_NOT_FOUND";
    private static final int HTTP_STATUS_REQUEST_TIMEOUT = 408;
    private static final int HTTP_STATUS_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_STATUS_SERVER_ERROR_MIN = 500;
    private static final String PATH_RUNTIME_EXPERIMENTS = "/runtime/experiments/";
    private static final String PATH_DATA_EVENT = "/data/event";
    private static final String PATH_DATA_EXPOSURE = "/data/exposure";
    private static final String COMPAT_VIEW_EVENT_TYPE = "VIEW";
    private static final String COMPAT_VIEW_EVENT_NAME = "product_view";
    private static final String COMPAT_CLICK_EVENT_TYPE = "CLICK";
    private static final String COMPAT_CLICK_EVENT_NAME = "contact_seller";
    private static final String COMPAT_CONVERT_EVENT_TYPE = "CONVERT";
    private static final String COMPAT_CONVERT_EVENT_NAME = "transaction_completed";

    private final String baseUrl;
    private final long timeoutMillis;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> defaultHeaders;
    private final long experimentCacheTtlMillis;
    private final boolean allowStaleExperimentConfig;
    private final long configVersionLongPollMillis;
    private final int maxRetries;
    private final long retryInitialBackoffMillis;
    private final long retryMaxBackoffMillis;
    private final double retryBackoffJitterRatio;
    private final Map<String, CachedExperimentConfig> experimentCache = new ConcurrentHashMap<>();
    private final AtomicLong requestAttemptCount = new AtomicLong();
    private final AtomicLong requestSuccessCount = new AtomicLong();
    private final AtomicLong requestFailureCount = new AtomicLong();
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong staleExperimentConfigFallbackCount = new AtomicLong();
    private final AtomicLong experimentCacheHitCount = new AtomicLong();
    private final AtomicLong experimentCacheMissCount = new AtomicLong();
    private final AtomicLong experimentVersionCheckCount = new AtomicLong();

    private PiscesClient(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.timeoutMillis = normalizeTimeoutMillis(builder.timeoutMillis);
        this.objectMapper = builder.objectMapper != null ? builder.objectMapper : new ObjectMapper();
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.timeoutMillis)).build();
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(builder.defaultHeaders));
        this.experimentCacheTtlMillis = normalizeExperimentCacheTtlMillis(builder.experimentCacheTtlMillis);
        this.allowStaleExperimentConfig = builder.allowStaleExperimentConfig;
        this.configVersionLongPollMillis =
                normalizeConfigVersionLongPollMillis(builder.configVersionLongPollMillis);
        this.maxRetries = normalizeMaxRetries(builder.maxRetries);
        this.retryInitialBackoffMillis = normalizeRetryInitialBackoffMillis(builder.retryInitialBackoffMillis);
        this.retryMaxBackoffMillis = normalizeRetryMaxBackoffMillis(builder.retryMaxBackoffMillis,
                this.retryInitialBackoffMillis);
        this.retryBackoffJitterRatio = normalizeRetryBackoffJitterRatio(builder.retryBackoffJitterRatio);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public PiscesClientMetricsSnapshot getMetricsSnapshot() {
        return PiscesClientMetricsSnapshot.of(requestAttemptCount.get(), requestSuccessCount.get(),
                requestFailureCount.get(), retryCount.get(), staleExperimentConfigFallbackCount.get(),
                experimentCacheHitCount.get(), experimentCacheMissCount.get(), experimentVersionCheckCount.get());
    }

    public void resetMetrics() {
        requestAttemptCount.set(0L);
        requestSuccessCount.set(0L);
        requestFailureCount.set(0L);
        retryCount.set(0L);
        staleExperimentConfigFallbackCount.set(0L);
        experimentCacheHitCount.set(0L);
        experimentCacheMissCount.set(0L);
        experimentVersionCheckCount.set(0L);
    }

    public String assignGroup(String experimentId, String visitorId) {
        return assignGroup(experimentId, visitorId, Collections.emptyMap());
    }

    public String assignGroup(String experimentId, String visitorId, Map<String, Object> attributes) {
        TrafficAssignRequest request = new TrafficAssignRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setAttributes(attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes));
        return sendRequest("/traffic/assign", METHOD_POST, request, String.class);
    }

    public TrafficAssignResponse assignGroupWithTrace(String experimentId, String visitorId) {
        return assignGroupWithTrace(experimentId, visitorId, Collections.emptyMap());
    }

    public TrafficAssignResponse assignGroupWithTrace(String experimentId, String visitorId,
                                                      Map<String, Object> attributes) {
        TrafficAssignRequest request = new TrafficAssignRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setAttributes(attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes));
        return sendRequest("/traffic/assign/trace", METHOD_POST, request, TrafficAssignResponse.class);
    }

    public ExperimentConfig getExperiment(String experimentId) {
        String normalizedExperimentId = requireText(experimentId, "experimentId不能为空");
        CachedExperimentConfig cachedExperimentConfig = experimentCache.get(normalizedExperimentId);
        if (isExperimentCacheFresh(cachedExperimentConfig)) {
            experimentCacheHitCount.incrementAndGet();
            return cachedExperimentConfig.experimentConfig();
        }
        experimentCacheMissCount.incrementAndGet();
        if (canReuseExpiredExperimentCache(cachedExperimentConfig)) {
            try {
                RuntimeConfigVersion configVersion = getExperimentConfigVersion(
                        normalizedExperimentId, cachedExperimentConfig.experimentConfig().getConfigVersion());
                if (configVersion != null && !Boolean.TRUE.equals(configVersion.getChanged())) {
                    cacheExperimentConfig(normalizedExperimentId, cachedExperimentConfig.experimentConfig());
                    return cachedExperimentConfig.experimentConfig();
                }
            } catch (PiscesSdkException exception) {
                if (allowStaleExperimentConfig) {
                    staleExperimentConfigFallbackCount.incrementAndGet();
                    return cachedExperimentConfig.experimentConfig();
                }
                throw exception;
            }
        }
        try {
            String requestPath = runtimeConfigPath(normalizedExperimentId);
            ExperimentConfig experimentConfig = sendRequest(
                    requestPath, METHOD_GET, null, ExperimentConfig.class);
            cacheExperimentConfig(normalizedExperimentId, experimentConfig);
            return experimentConfig;
        } catch (PiscesSdkException exception) {
            if (allowStaleExperimentConfig && cachedExperimentConfig != null) {
                staleExperimentConfigFallbackCount.incrementAndGet();
                return cachedExperimentConfig.experimentConfig();
            }
            throw exception;
        }
    }

    public void clearExperimentCache() {
        experimentCache.clear();
    }

    public void clearExperimentCache(String experimentId) {
        experimentCache.remove(requireText(experimentId, "experimentId不能为空"));
    }

    public List<EventDefinition> getEventDefinitions(String experimentId) {
        ExperimentConfig experimentConfig = getExperiment(experimentId);
        return experimentConfig.getEventDefinitions() == null
                ? Collections.emptyList()
                : List.copyOf(experimentConfig.getEventDefinitions());
    }

    public List<MetricDefinition> getMetricDefinitions(String experimentId) {
        ExperimentConfig experimentConfig = getExperiment(experimentId);
        return experimentConfig.getMetricDefinitions() == null
                ? Collections.emptyList()
                : List.copyOf(experimentConfig.getMetricDefinitions());
    }

    public List<GroupConfigFieldDefinition> getGroupConfigSchema(String experimentId) {
        ExperimentConfig experimentConfig = getExperiment(experimentId);
        return experimentConfig.getGroupConfigSchema() == null
                ? Collections.emptyList()
                : List.copyOf(experimentConfig.getGroupConfigSchema());
    }

    public Map<String, Object> getGroupConfig(String experimentId, String visitorId) {
        return getGroupConfig(experimentId, visitorId, Collections.emptyMap());
    }

    public Map<String, Object> getGroupConfig(String experimentId, String visitorId, Map<String, Object> attributes) {
        TrafficAssignResponse assignment = assignGroupWithTrace(experimentId, visitorId, attributes);
        String groupId = assignment.getGroupId();
        ExperimentConfig experimentConfig = getExperimentForAssignment(experimentId, assignment);
        Map<String, ExperimentGroupConfig> groups = experimentConfig.getGroups();
        ExperimentGroupConfig groupConfig = groups == null ? null : groups.get(groupId);
        if (groupConfig == null) {
            throw new PiscesSdkException("未找到实验组配置", CODE_GROUP_NOT_FOUND, null,
                    runtimeConfigPath(experimentId), groupId);
        }
        return groupConfig.getConfig() == null ? Collections.emptyMap() : new LinkedHashMap<>(groupConfig.getConfig());
    }

    public void reportExposure(String experimentId, String visitorId, Map<String, Object> properties) {
        ExposureReportRequest request = new ExposureReportRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setProperties(properties == null ? Collections.emptyMap() : new LinkedHashMap<>(properties));
        sendRequest(PATH_DATA_EXPOSURE, METHOD_POST, request, Object.class);
    }

    public void reportEvent(String experimentId, String visitorId, String eventType, String eventName,
                            Map<String, Object> properties) {
        EventReportRequest request = new EventReportRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setEventType(requireText(eventType, "eventType不能为空"));
        request.setEventName(requireText(eventName, "eventName不能为空"));
        request.setProperties(properties == null ? Collections.emptyMap() : new LinkedHashMap<>(properties));
        sendRequest(PATH_DATA_EVENT, METHOD_POST, request, Object.class);
    }

    public void reportEventByKey(String experimentId, String visitorId, String eventKey,
                                 Map<String, Object> properties) {
        String normalizedEventKey = requireText(eventKey, "eventKey不能为空");
        reportEvent(experimentId, visitorId, normalizedEventKey, normalizedEventKey, properties);
    }

    public void reportView(String experimentId, String visitorId, Map<String, Object> properties) {
        reportEvent(experimentId, visitorId, COMPAT_VIEW_EVENT_TYPE, COMPAT_VIEW_EVENT_NAME, properties);
    }

    public void reportClick(String experimentId, String visitorId, Map<String, Object> properties) {
        reportEvent(experimentId, visitorId, COMPAT_CLICK_EVENT_TYPE, COMPAT_CLICK_EVENT_NAME, properties);
    }

    public void reportConvert(String experimentId, String visitorId, Map<String, Object> properties) {
        reportEvent(experimentId, visitorId, COMPAT_CONVERT_EVENT_TYPE, COMPAT_CONVERT_EVENT_NAME, properties);
    }

    private <T> T sendRequest(String path, String method, Object requestBody, Class<T> responseType) {
        PiscesSdkException lastException = null;
        for (int attemptIndex = 0; attemptIndex <= maxRetries; attemptIndex++) {
            if (attemptIndex > 0) {
                retryCount.incrementAndGet();
                sleepBeforeRetry(attemptIndex);
            }
            try {
                requestAttemptCount.incrementAndGet();
                T response = sendRequestOnce(path, method, requestBody, responseType);
                requestSuccessCount.incrementAndGet();
                return response;
            } catch (PiscesSdkException exception) {
                requestFailureCount.incrementAndGet();
                lastException = exception;
                if (!shouldRetry(exception, attemptIndex)) {
                    throw exception;
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new PiscesSdkException("Pisces SDK请求失败", CODE_REQUEST_ERROR, null, path, null);
    }

    private <T> T sendRequestOnce(String path, String method, Object requestBody, Class<T> responseType) {
        HttpRequest request = buildRequest(path, method, requestBody);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new PiscesSdkException("Pisces SDK请求失败", CODE_IO_ERROR, null, path, null, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PiscesSdkException("Pisces SDK请求被中断", CODE_INTERRUPTED, null, path, null, exception);
        }
        return unwrapResponse(path, response, responseType);
    }

    private HttpRequest buildRequest(String path, String method, Object requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMillis));
        defaultHeaders.forEach(builder::header);
        if (METHOD_GET.equals(method)) {
            return builder.GET().build();
        }
        String body = serializeRequestBody(requestBody);
        builder.header(CONTENT_TYPE_HEADER, CONTENT_TYPE);
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private String serializeRequestBody(Object requestBody) {
        if (requestBody == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException exception) {
            throw new PiscesSdkException("Pisces SDK请求序列化失败", CODE_SERIALIZE_ERROR, null, null, null,
                    exception);
        }
    }

    private <T> T unwrapResponse(String path, HttpResponse<String> response, Class<T> responseType) {
        String responseBody = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PiscesSdkException("Pisces SDK请求失败", CODE_HTTP_ERROR,
                    response.statusCode(), path, responseBody);
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new PiscesSdkException("Pisces SDK响应体为空", CODE_EMPTY_RESPONSE,
                    response.statusCode(), path, responseBody);
        }
        BaseResponse<T> baseResponse = parseBaseResponse(responseBody, responseType);
        if (!Objects.equals(baseResponse.getCode(), SUCCESS_CODE)) {
            throw new PiscesSdkException(baseResponse.getMessage(), String.valueOf(baseResponse.getCode()),
                    response.statusCode(), path, responseBody);
        }
        return baseResponse.getData();
    }

    private <T> BaseResponse<T> parseBaseResponse(String responseBody, Class<T> responseType) {
        JavaType dataJavaType = objectMapper.getTypeFactory().constructType(responseType);
        JavaType baseResponseType = objectMapper.getTypeFactory()
                .constructParametricType(BaseResponse.class, dataJavaType);
        try {
            return objectMapper.readValue(responseBody, baseResponseType);
        } catch (JsonProcessingException exception) {
            throw new PiscesSdkException("Pisces SDK响应解析失败", CODE_INVALID_RESPONSE,
                    null, null, responseBody, exception);
        }
    }

    private boolean shouldRetry(PiscesSdkException exception, int attemptIndex) {
        return attemptIndex < maxRetries && isRetryable(exception);
    }

    private boolean isRetryable(PiscesSdkException exception) {
        if (exception == null || CODE_INTERRUPTED.equals(exception.getCode())) {
            return false;
        }
        if (CODE_IO_ERROR.equals(exception.getCode()) || CODE_EMPTY_RESPONSE.equals(exception.getCode())) {
            return true;
        }
        Integer httpStatus = exception.getHttpStatus();
        if (httpStatus != null && (httpStatus == HTTP_STATUS_REQUEST_TIMEOUT
                || httpStatus == HTTP_STATUS_TOO_MANY_REQUESTS
                || httpStatus >= HTTP_STATUS_SERVER_ERROR_MIN)) {
            return true;
        }
        return isRetryableBusinessCode(exception.getCode());
    }

    private boolean isRetryableBusinessCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        try {
            int numericCode = Integer.parseInt(code);
            return numericCode == HTTP_STATUS_REQUEST_TIMEOUT
                    || numericCode == HTTP_STATUS_TOO_MANY_REQUESTS
                    || numericCode >= HTTP_STATUS_SERVER_ERROR_MIN;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void sleepBeforeRetry(int retryNumber) {
        long delayMillis = calculateRetryDelayMillis(retryNumber);
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PiscesSdkException("Pisces SDK请求被中断", CODE_INTERRUPTED, null, null, null, exception);
        }
    }

    private long calculateRetryDelayMillis(int retryNumber) {
        long delayMillis = retryInitialBackoffMillis;
        for (int index = 1; index < retryNumber; index++) {
            if (delayMillis >= retryMaxBackoffMillis / 2L) {
                delayMillis = retryMaxBackoffMillis;
                break;
            }
            delayMillis *= 2L;
        }
        delayMillis = Math.min(delayMillis, retryMaxBackoffMillis);
        if (delayMillis <= 0 || retryBackoffJitterRatio <= 0D) {
            return delayMillis;
        }
        long jitterMillis = Math.round(delayMillis * retryBackoffJitterRatio);
        long minDelayMillis = Math.max(0L, delayMillis - jitterMillis);
        long maxDelayMillis = delayMillis + jitterMillis;
        return ThreadLocalRandom.current().nextLong(minDelayMillis, maxDelayMillis + 1L);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = requireText(baseUrl, "Pisces SDK baseUrl不能为空");
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static long normalizeTimeoutMillis(Long timeoutMillis) {
        if (timeoutMillis == null) {
            return DEFAULT_TIMEOUT_MILLIS;
        }
        if (timeoutMillis <= 0) {
            throw new PiscesSdkException("Pisces SDK timeoutMillis必须大于0");
        }
        return timeoutMillis;
    }

    private static long normalizeExperimentCacheTtlMillis(Long experimentCacheTtlMillis) {
        if (experimentCacheTtlMillis == null) {
            return DEFAULT_EXPERIMENT_CACHE_TTL_MILLIS;
        }
        if (experimentCacheTtlMillis < 0) {
            throw new PiscesSdkException("Pisces SDK experimentCacheTtlMillis不能小于0");
        }
        return experimentCacheTtlMillis;
    }

    private static long normalizeConfigVersionLongPollMillis(Long configVersionLongPollMillis) {
        if (configVersionLongPollMillis == null) {
            return DEFAULT_CONFIG_VERSION_LONG_POLL_MILLIS;
        }
        if (configVersionLongPollMillis < 0) {
            throw new PiscesSdkException("Pisces SDK configVersionLongPollMillis不能小于0");
        }
        return configVersionLongPollMillis;
    }

    private static int normalizeMaxRetries(Integer maxRetries) {
        if (maxRetries == null) {
            return DEFAULT_MAX_RETRIES;
        }
        if (maxRetries < 0) {
            throw new PiscesSdkException("Pisces SDK maxRetries不能小于0");
        }
        return maxRetries;
    }

    private static long normalizeRetryInitialBackoffMillis(Long retryInitialBackoffMillis) {
        if (retryInitialBackoffMillis == null) {
            return DEFAULT_RETRY_INITIAL_BACKOFF_MILLIS;
        }
        if (retryInitialBackoffMillis < 0) {
            throw new PiscesSdkException("Pisces SDK retryInitialBackoffMillis不能小于0");
        }
        return retryInitialBackoffMillis;
    }

    private static long normalizeRetryMaxBackoffMillis(Long retryMaxBackoffMillis,
                                                       long retryInitialBackoffMillis) {
        if (retryMaxBackoffMillis == null) {
            return DEFAULT_RETRY_MAX_BACKOFF_MILLIS;
        }
        if (retryMaxBackoffMillis < retryInitialBackoffMillis) {
            throw new PiscesSdkException("Pisces SDK retryMaxBackoffMillis不能小于初始退避时间");
        }
        return retryMaxBackoffMillis;
    }

    private static double normalizeRetryBackoffJitterRatio(Double retryBackoffJitterRatio) {
        if (retryBackoffJitterRatio == null) {
            return DEFAULT_RETRY_BACKOFF_JITTER_RATIO;
        }
        if (retryBackoffJitterRatio < 0D || retryBackoffJitterRatio > 1D) {
            throw new PiscesSdkException("Pisces SDK retryBackoffJitterRatio必须在0到1之间");
        }
        return retryBackoffJitterRatio;
    }

    private ExperimentConfig getExperimentForAssignment(String experimentId, TrafficAssignResponse assignment) {
        String normalizedExperimentId = requireText(experimentId, "experimentId不能为空");
        ExperimentConfig experimentConfig = getExperiment(normalizedExperimentId);
        if (assignment == null
                || assignment.getConfigVersion() == null
                || experimentConfig == null
                || experimentConfig.getConfigVersion() == null
                || Objects.equals(assignment.getConfigVersion(), experimentConfig.getConfigVersion())) {
            return experimentConfig;
        }
        ExperimentConfig staleExperimentConfig = experimentConfig;
        clearExperimentCache(normalizedExperimentId);
        try {
            return getExperiment(normalizedExperimentId);
        } catch (PiscesSdkException exception) {
            if (canUseStaleExperimentConfigForAssignment(staleExperimentConfig, assignment)) {
                staleExperimentConfigFallbackCount.incrementAndGet();
                cacheExperimentConfig(normalizedExperimentId, staleExperimentConfig);
                return staleExperimentConfig;
            }
            throw exception;
        }
    }

    private boolean canUseStaleExperimentConfigForAssignment(ExperimentConfig experimentConfig,
                                                             TrafficAssignResponse assignment) {
        if (!allowStaleExperimentConfig
                || experimentConfig == null
                || assignment == null
                || assignment.getGroupId() == null
                || assignment.getGroupId().isBlank()
                || experimentConfig.getGroups() == null) {
            return false;
        }
        return experimentConfig.getGroups().containsKey(assignment.getGroupId());
    }

    private boolean isExperimentCacheFresh(CachedExperimentConfig cachedExperimentConfig) {
        return cachedExperimentConfig != null
                && experimentCacheTtlMillis > 0
                && cachedExperimentConfig.expiresAtMillis() > System.currentTimeMillis();
    }

    private boolean canReuseExpiredExperimentCache(CachedExperimentConfig cachedExperimentConfig) {
        return cachedExperimentConfig != null
                && experimentCacheTtlMillis > 0
                && cachedExperimentConfig.experimentConfig() != null
                && cachedExperimentConfig.experimentConfig().getConfigVersion() != null;
    }

    private RuntimeConfigVersion getExperimentConfigVersion(String experimentId, Long knownVersion) {
        experimentVersionCheckCount.incrementAndGet();
        return sendRequest(runtimeConfigVersionPath(experimentId, knownVersion), METHOD_GET, null,
                RuntimeConfigVersion.class);
    }

    private void cacheExperimentConfig(String experimentId, ExperimentConfig experimentConfig) {
        if (experimentCacheTtlMillis <= 0 || experimentConfig == null) {
            return;
        }
        experimentCache.put(experimentId, new CachedExperimentConfig(
                experimentConfig, System.currentTimeMillis() + experimentCacheTtlMillis));
    }

    private static String runtimeConfigPath(String experimentId) {
        return PATH_RUNTIME_EXPERIMENTS + requireText(experimentId, "experimentId不能为空") + "/config";
    }

    private String runtimeConfigVersionPath(String experimentId, Long knownVersion) {
        String path = runtimeConfigPath(experimentId) + "/version";
        StringBuilder query = new StringBuilder();
        if (knownVersion != null) {
            query.append("knownVersion=").append(knownVersion);
        }
        if (configVersionLongPollMillis > 0) {
            if (!query.isEmpty()) {
                query.append("&");
            }
            query.append("waitMillis=").append(configVersionLongPollMillis);
        }
        return query.isEmpty() ? path : path + "?" + query;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PiscesSdkException(message);
        }
        return value.trim();
    }

    public static final class Builder {
        private String baseUrl;
        private Long timeoutMillis;
        private HttpClient httpClient;
        private ObjectMapper objectMapper;
        private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private Long experimentCacheTtlMillis;
        private boolean allowStaleExperimentConfig;
        private Long configVersionLongPollMillis;
        private Integer maxRetries;
        private Long retryInitialBackoffMillis;
        private Long retryMaxBackoffMillis;
        private Double retryBackoffJitterRatio;

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder defaultHeader(String name, String value) {
            if (name != null && !name.isBlank() && value != null) {
                defaultHeaders.put(name.trim(), value);
            }
            return this;
        }

        public Builder experimentCacheTtlMillis(long experimentCacheTtlMillis) {
            this.experimentCacheTtlMillis = experimentCacheTtlMillis;
            return this;
        }

        public Builder allowStaleExperimentConfig(boolean allowStaleExperimentConfig) {
            this.allowStaleExperimentConfig = allowStaleExperimentConfig;
            return this;
        }

        public Builder configVersionLongPollMillis(long configVersionLongPollMillis) {
            this.configVersionLongPollMillis = configVersionLongPollMillis;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryInitialBackoffMillis(long retryInitialBackoffMillis) {
            this.retryInitialBackoffMillis = retryInitialBackoffMillis;
            return this;
        }

        public Builder retryMaxBackoffMillis(long retryMaxBackoffMillis) {
            this.retryMaxBackoffMillis = retryMaxBackoffMillis;
            return this;
        }

        public Builder retryBackoffJitterRatio(double retryBackoffJitterRatio) {
            this.retryBackoffJitterRatio = retryBackoffJitterRatio;
            return this;
        }

        public PiscesClient build() {
            return new PiscesClient(this);
        }
    }

    private record CachedExperimentConfig(ExperimentConfig experimentConfig, long expiresAtMillis) {
    }
}
