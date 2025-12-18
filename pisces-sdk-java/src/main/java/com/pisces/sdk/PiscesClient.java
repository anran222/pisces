package com.pisces.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Pisces A/B测试 Java SDK
 * 无需用户认证，使用visitorId即可
 */
@Slf4j
public class PiscesClient {
    
    private final String apiBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public PiscesClient(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") 
            ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) 
            : apiBaseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 分配访客到实验组
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识
     * @return 实验组ID
     */
    public String assignGroup(String experimentId, String visitorId) {
        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("experimentId", experimentId);
            requestBody.put("visitorId", visitorId);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/traffic/assign"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                return (String) data;
            } else {
                log.error("分配实验组失败: status={}, body={}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("分配实验组异常: experimentId={}, visitorId={}", experimentId, visitorId, e);
            return null;
        }
    }
    
    /**
     * 获取实验配置
     * @param experimentId 实验ID
     * @return 实验配置
     */
    public ExperimentConfig getExperiment(String experimentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/experiments/" + experimentId))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                return objectMapper.convertValue(data, ExperimentConfig.class);
            } else {
                log.error("获取实验配置失败: status={}, body={}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("获取实验配置异常: experimentId={}", experimentId, e);
            return null;
        }
    }
    
    /**
     * 上报事件
     * @param experimentId 实验ID
     * @param visitorId 访客ID
     * @param eventType 事件类型
     * @param eventName 事件名称
     * @param properties 事件属性
     */
    public void reportEvent(String experimentId, String visitorId, String eventType,
                           String eventName, Map<String, Object> properties) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("experimentId", experimentId);
            requestBody.put("visitorId", visitorId);
            requestBody.put("eventType", eventType);
            requestBody.put("eventName", eventName);
            requestBody.put("properties", properties);
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/data/event"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("上报事件失败: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("上报事件异常: experimentId={}, visitorId={}", experimentId, visitorId, e);
        }
    }
    
    /**
     * 上报浏览事件
     */
    public void reportView(String experimentId, String visitorId, Map<String, Object> productData) {
        reportEvent(experimentId, visitorId, "VIEW", "product_view", productData);
    }
    
    /**
     * 上报咨询事件
     */
    public void reportClick(String experimentId, String visitorId, Map<String, Object> clickData) {
        reportEvent(experimentId, visitorId, "CLICK", "contact_seller", clickData);
    }
    
    /**
     * 上报成交事件（关键指标）
     */
    public void reportTransaction(String experimentId, String visitorId, Map<String, Object> transactionData) {
        Double transactionPrice = ((Number) transactionData.get("transactionPrice")).doubleValue();
        Double marketPrice = ((Number) transactionData.get("marketPrice")).doubleValue();
        Double priceRatio = marketPrice > 0 ? transactionPrice / marketPrice : 0.0;
        
        transactionData.put("priceRatio", priceRatio);
        reportEvent(experimentId, visitorId, "CONVERT", "transaction_completed", transactionData);
    }
}
