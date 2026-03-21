package com.pisces.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.sdk.exception.PiscesSdkException;
import com.pisces.sdk.model.ExperimentConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiscesClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldBuildClientWithBaseUrl() {
        PiscesClient client = PiscesClient.builder()
                .baseUrl("http://localhost:9990/api")
                .build();

        assertEquals("http://localhost:9990/api", client.getBaseUrl());
    }

    @Test
    void shouldRejectBlankBaseUrl() {
        PiscesSdkException exception = assertThrows(PiscesSdkException.class,
                () -> PiscesClient.builder().baseUrl(" ").build());

        assertEquals("Pisces SDK baseUrl不能为空", exception.getMessage());
    }

    @Test
    void shouldUnwrapBusinessSuccessResponse() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":200,"message":"操作成功","data":"group_b","timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            String groupId = client.assignGroup("exp_001", "visitor_001");

            assertEquals("group_b", groupId);
        }
    }

    @Test
    void shouldThrowWhenBusinessCodeIsNotSuccess() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":500,"message":"实验不存在","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            PiscesSdkException exception = assertThrows(PiscesSdkException.class,
                    () -> client.assignGroup("exp_001", "visitor_001"));

            assertEquals("实验不存在", exception.getMessage());
            assertEquals("500", exception.getCode());
            assertEquals("/traffic/assign", exception.getRequestPath());
        }
    }

    @Test
    void shouldAssignGroupWithAttributes() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":200,"message":"操作成功","data":"group_a","timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            String groupId = client.assignGroup("exp_assign", "visitor_123", Map.of("city", "shanghai"));

            assertEquals("group_a", groupId);
            assertEquals("/api/traffic/assign", server.getLastPath());
            assertEquals("POST", server.getLastMethod());
            assertEquals("exp_assign", server.getLastRequestBodyAsMap().get("experimentId"));
            assertEquals("visitor_123", server.getLastRequestBodyAsMap().get("visitorId"));
            assertEquals(Map.of("city", "shanghai"), server.getLastRequestBodyAsMap().get("attributes"));
        }
    }

    @Test
    void shouldGetExperimentConfig() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {
                      "code":200,
                      "message":"操作成功",
                      "data":{
                        "id":"exp_001",
                        "name":"价格实验",
                        "status":"RUNNING",
                        "eventDefinitions":[
                          {
                            "key":"PRODUCT_VIEW",
                            "label":"商品查看",
                            "description":"进入商品详情页",
                            "category":"FUNNEL",
                            "primary":true
                          }
                        ],
                        "metricDefinitions":[
                          {
                            "key":"PAYMENT_RATE",
                            "name":"支付率",
                            "description":"支付成功占查看比率",
                            "aggregationType":"RATE",
                            "numeratorEventType":"PAY_SUCCESS",
                            "denominatorType":"EVENT_COUNT",
                            "denominatorEventType":"PRODUCT_VIEW",
                            "primaryMetric":true,
                            "guardrailMetric":false
                          }
                        ],
                        "groupConfigSchema":[
                          {
                            "key":"mainTitle",
                            "label":"主标题",
                            "valueType":"STRING",
                            "required":true,
                            "description":"商品卡标题",
                            "defaultValue":"默认主标题"
                          }
                        ],
                        "groups":{
                          "group_a":{
                            "id":"group_a",
                            "name":"对照组",
                            "trafficRatio":0.5,
                            "config":{"price":"100"}
                          }
                        }
                      },
                      "timestamp":1
                    }
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            ExperimentConfig experiment = client.getExperiment("exp_001");

            assertEquals("/api/experiments/exp_001", server.getLastPath());
            assertEquals("GET", server.getLastMethod());
            assertEquals("exp_001", experiment.getId());
            assertEquals("价格实验", experiment.getName());
            assertEquals("PRODUCT_VIEW", experiment.getEventDefinitions().get(0).getKey());
            assertEquals("PAYMENT_RATE", experiment.getMetricDefinitions().get(0).getKey());
            assertEquals("mainTitle", experiment.getGroupConfigSchema().get(0).getKey());
            assertEquals("100", experiment.getGroups().get("group_a").getConfig().get("price"));
        }
    }

    @Test
    void shouldGetGroupConfigSchema() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {
                      "code":200,
                      "message":"操作成功",
                      "data":{
                        "id":"exp_001",
                        "name":"价格实验",
                        "status":"RUNNING",
                        "groupConfigSchema":[
                          {
                            "key":"mainTitle",
                            "label":"主标题",
                            "valueType":"STRING",
                            "required":true,
                            "description":"商品卡标题",
                            "defaultValue":"默认主标题"
                          },
                          {
                            "key":"badgeCount",
                            "label":"标签数量",
                            "valueType":"INTEGER",
                            "required":false,
                            "description":"展示的标签数量",
                            "defaultValue":2
                          }
                        ]
                      },
                      "timestamp":1
                    }
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            assertEquals(2, client.getGroupConfigSchema("exp_001").size());
            assertEquals("badgeCount", client.getGroupConfigSchema("exp_001").get(1).getKey());
        }
    }

    @Test
    void shouldGetEventDefinitionsAndMetricDefinitions() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {
                      "code":200,
                      "message":"操作成功",
                      "data":{
                        "id":"exp_001",
                        "name":"价格实验",
                        "status":"RUNNING",
                        "eventDefinitions":[
                          {
                            "key":"PRODUCT_VIEW",
                            "label":"商品查看",
                            "description":"进入商品详情页",
                            "category":"FUNNEL",
                            "primary":true
                          },
                          {
                            "key":"PAY_SUCCESS",
                            "label":"支付成功",
                            "description":"完成支付",
                            "category":"RESULT",
                            "primary":false
                          }
                        ],
                        "metricDefinitions":[
                          {
                            "key":"PAYMENT_RATE",
                            "name":"支付率",
                            "description":"支付成功占查看比率",
                            "aggregationType":"RATE",
                            "numeratorEventType":"PAY_SUCCESS",
                            "denominatorType":"EVENT_COUNT",
                            "denominatorEventType":"PRODUCT_VIEW",
                            "primaryMetric":true,
                            "guardrailMetric":false
                          }
                        ]
                      },
                      "timestamp":1
                    }
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            assertEquals(2, client.getEventDefinitions("exp_001").size());
            assertEquals("PRODUCT_VIEW", client.getEventDefinitions("exp_001").get(0).getKey());
            assertEquals(1, client.getMetricDefinitions("exp_001").size());
            assertEquals("PAYMENT_RATE", client.getMetricDefinitions("exp_001").get(0).getKey());
        }
    }

    @Test
    void shouldResolveGroupConfig() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.queueResponse(200, """
                    {"code":200,"message":"操作成功","data":"group_b","timestamp":1}
                    """);
            server.queueResponse(200, """
                    {
                      "code":200,
                      "message":"操作成功",
                      "data":{
                        "id":"exp_001",
                        "name":"价格实验",
                        "status":"RUNNING",
                        "groups":{
                          "group_b":{
                            "id":"group_b",
                            "name":"实验组",
                            "trafficRatio":0.5,
                            "config":{"discount":"15%"}
                          }
                        }
                      },
                      "timestamp":1
                    }
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            Map<String, Object> groupConfig = client.getGroupConfig("exp_001", "visitor_001");

            assertEquals("15%", groupConfig.get("discount"));
            assertEquals("/api/experiments/exp_001", server.getLastPath());
        }
    }

    @Test
    void shouldReportExposure() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":200,"message":"曝光上报成功","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            client.reportExposure("exp_001", "visitor_001", Map.of("page", "detail"));

            assertEquals("/api/data/exposure", server.getLastPath());
            assertEquals("POST", server.getLastMethod());
            assertEquals("detail", ((Map<?, ?>) server.getLastRequestBodyAsMap().get("properties")).get("page"));
        }
    }

    @Test
    void shouldReportEventAndShortcutMethods() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.queueResponse(200, """
                    {"code":200,"message":"事件上报成功","data":null,"timestamp":1}
                    """);
            server.queueResponse(200, """
                    {"code":200,"message":"事件上报成功","data":null,"timestamp":1}
                    """);
            server.queueResponse(200, """
                    {"code":200,"message":"事件上报成功","data":null,"timestamp":1}
                    """);
            server.queueResponse(200, """
                    {"code":200,"message":"事件上报成功","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            client.reportEvent("exp_001", "visitor_001", "CUSTOM", "submit_form", Map.of("step", "confirm"));
            assertEquals("/api/data/event", server.getLastPath());
            assertEquals("CUSTOM", server.getLastRequestBodyAsMap().get("eventType"));
            assertEquals("submit_form", server.getLastRequestBodyAsMap().get("eventName"));

            client.reportView("exp_001", "visitor_001", Map.of("productId", "p1"));
            assertEquals("VIEW", server.getLastRequestBodyAsMap().get("eventType"));
            assertEquals("product_view", server.getLastRequestBodyAsMap().get("eventName"));

            client.reportClick("exp_001", "visitor_001", Map.of("productId", "p1"));
            assertEquals("CLICK", server.getLastRequestBodyAsMap().get("eventType"));
            assertEquals("contact_seller", server.getLastRequestBodyAsMap().get("eventName"));

            client.reportConvert("exp_001", "visitor_001", Map.of("transactionId", "t1"));
            assertEquals("CONVERT", server.getLastRequestBodyAsMap().get("eventType"));
            assertEquals("transaction_completed", server.getLastRequestBodyAsMap().get("eventName"));
        }
    }

    @Test
    void shouldReportEventByKey() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":200,"message":"事件上报成功","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            client.reportEventByKey("exp_001", "visitor_001", "PAY_SUCCESS", Map.of("orderId", "ord_001"));

            assertEquals("/api/data/event", server.getLastPath());
            assertEquals("PAY_SUCCESS", server.getLastRequestBodyAsMap().get("eventType"));
            assertEquals("PAY_SUCCESS", server.getLastRequestBodyAsMap().get("eventName"));
        }
    }

    @Test
    void shouldReturnNullDataWhenResponseBodyContainsNullData() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(200, """
                    {"code":200,"message":"成功","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            Object response = client.getExperiment("exp_001");

            assertNull(response);
        }
    }

    @Test
    void shouldThrowWhenHttpStatusIsNotSuccess() throws Exception {
        try (HttpTestServer server = HttpTestServer.start()) {
            server.respondWith(500, """
                    {"code":500,"message":"系统异常","data":null,"timestamp":1}
                    """);
            PiscesClient client = PiscesClient.builder().baseUrl(server.baseUrl()).build();

            PiscesSdkException exception = assertThrows(PiscesSdkException.class,
                    () -> client.getExperiment("exp_001"));

            assertEquals("HTTP_ERROR", exception.getCode());
            assertEquals(500, exception.getHttpStatus());
        }
    }

    private static final class HttpTestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastPath = new AtomicReference<>();
        private final AtomicReference<String> lastMethod = new AtomicReference<>();
        private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
        private final AtomicReference<ResponseSpec> nextResponse = new AtomicReference<>();
        private final java.util.Queue<ResponseSpec> queuedResponses = new java.util.ArrayDeque<>();

        private HttpTestServer(HttpServer server) {
            this.server = server;
        }

        static HttpTestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            HttpTestServer httpTestServer = new HttpTestServer(server);
            server.createContext("/", httpTestServer::handle);
            server.start();
            return httpTestServer;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        }

        void respondWith(int status, String body) {
            nextResponse.set(new ResponseSpec(status, body));
        }

        void queueResponse(int status, String body) {
            queuedResponses.add(new ResponseSpec(status, body));
        }

        String getLastPath() {
            return lastPath.get();
        }

        String getLastMethod() {
            return lastMethod.get();
        }

        Map<String, Object> getLastRequestBodyAsMap() throws IOException {
            String body = lastRequestBody.get();
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            return OBJECT_MAPPER.readValue(body, OBJECT_MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        }

        private void handle(HttpExchange exchange) throws IOException {
            lastPath.set(exchange.getRequestURI().getPath());
            lastMethod.set(exchange.getRequestMethod());
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ResponseSpec responseSpec = queuedResponses.poll();
            if (responseSpec == null) {
                responseSpec = nextResponse.get();
            }
            if (responseSpec == null) {
                responseSpec = new ResponseSpec(200, "{\"code\":200,\"message\":\"ok\",\"data\":null,\"timestamp\":1}");
            }
            byte[] payload = responseSpec.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseSpec.status(), payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record ResponseSpec(int status, String body) {
    }
}
