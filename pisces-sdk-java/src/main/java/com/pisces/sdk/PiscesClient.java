package com.pisces.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.sdk.exception.PiscesSdkException;
import com.pisces.sdk.model.BaseResponse;
import com.pisces.sdk.model.EventReportRequest;
import com.pisces.sdk.model.ExperimentConfig;
import com.pisces.sdk.model.ExperimentGroupConfig;
import com.pisces.sdk.model.EventDefinition;
import com.pisces.sdk.model.GroupConfigFieldDefinition;
import com.pisces.sdk.model.MetricDefinition;
import com.pisces.sdk.model.ExposureReportRequest;
import com.pisces.sdk.model.TrafficAssignRequest;

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
    private static final String PATH_EXPERIMENTS = "/experiments/";
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

    private PiscesClient(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.timeoutMillis = normalizeTimeoutMillis(builder.timeoutMillis);
        this.objectMapper = builder.objectMapper != null ? builder.objectMapper : new ObjectMapper();
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.timeoutMillis)).build();
        this.defaultHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(builder.defaultHeaders));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String assignGroup(String experimentId, String visitorId) {
        return assignGroup(experimentId, visitorId, Collections.emptyMap());
    }

    public String assignGroup(String experimentId, String visitorId, Map<String, Object> attributes) {
        TrafficAssignRequest request = new TrafficAssignRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setAttributes(attributes == null ? Collections.emptyMap() : new LinkedHashMap<>(attributes));
        return sendRequest("/traffic/assign", "POST", request, String.class);
    }

    public ExperimentConfig getExperiment(String experimentId) {
        requireText(experimentId, "experimentId不能为空");
        return sendRequest(PATH_EXPERIMENTS + experimentId, "GET", null, ExperimentConfig.class);
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
        String groupId = assignGroup(experimentId, visitorId, attributes);
        ExperimentConfig experimentConfig = getExperiment(experimentId);
        Map<String, ExperimentGroupConfig> groups = experimentConfig.getGroups();
        ExperimentGroupConfig groupConfig = groups == null ? null : groups.get(groupId);
        if (groupConfig == null) {
            throw new PiscesSdkException("未找到实验组配置", "GROUP_NOT_FOUND", null,
                    PATH_EXPERIMENTS + experimentId, groupId);
        }
        return groupConfig.getConfig() == null ? Collections.emptyMap() : new LinkedHashMap<>(groupConfig.getConfig());
    }

    public void reportExposure(String experimentId, String visitorId, Map<String, Object> properties) {
        ExposureReportRequest request = new ExposureReportRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setProperties(properties == null ? Collections.emptyMap() : new LinkedHashMap<>(properties));
        sendRequest(PATH_DATA_EXPOSURE, "POST", request, Object.class);
    }

    public void reportEvent(String experimentId, String visitorId, String eventType, String eventName,
                            Map<String, Object> properties) {
        EventReportRequest request = new EventReportRequest();
        request.setExperimentId(requireText(experimentId, "experimentId不能为空"));
        request.setVisitorId(requireText(visitorId, "visitorId不能为空"));
        request.setEventType(requireText(eventType, "eventType不能为空"));
        request.setEventName(requireText(eventName, "eventName不能为空"));
        request.setProperties(properties == null ? Collections.emptyMap() : new LinkedHashMap<>(properties));
        sendRequest(PATH_DATA_EVENT, "POST", request, Object.class);
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
        HttpRequest request = buildRequest(path, method, requestBody);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new PiscesSdkException("Pisces SDK请求失败", "IO_ERROR", null, path, null, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PiscesSdkException("Pisces SDK请求被中断", "INTERRUPTED", null, path, null, exception);
        }
        return unwrapResponse(path, response, responseType);
    }

    private HttpRequest buildRequest(String path, String method, Object requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMillis));
        defaultHeaders.forEach(builder::header);
        if ("GET".equals(method)) {
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
            throw new PiscesSdkException("Pisces SDK请求序列化失败", "SERIALIZE_ERROR", null, null, null, exception);
        }
    }

    private <T> T unwrapResponse(String path, HttpResponse<String> response, Class<T> responseType) {
        String responseBody = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PiscesSdkException("Pisces SDK请求失败", "HTTP_ERROR",
                    response.statusCode(), path, responseBody);
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new PiscesSdkException("Pisces SDK响应体为空", "EMPTY_RESPONSE",
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
            throw new PiscesSdkException("Pisces SDK响应解析失败", "INVALID_RESPONSE",
                    null, null, responseBody, exception);
        }
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

        public PiscesClient build() {
            return new PiscesClient(this);
        }
    }
}
