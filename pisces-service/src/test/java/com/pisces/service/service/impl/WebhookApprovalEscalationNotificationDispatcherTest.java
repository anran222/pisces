package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.service.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookApprovalEscalationNotificationDispatcherTest {

    @Test
    void dispatchShouldFanOutToMultipleWebhookTargets() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger larkCount = new AtomicInteger();
        AtomicInteger slackCount = new AtomicInteger();
        AtomicReference<String> larkBody = new AtomicReference<>();
        AtomicReference<String> slackBody = new AtomicReference<>();
        server.createContext("/lark", exchange -> handleWebhook(exchange, larkCount, larkBody));
        server.createContext("/slack", exchange -> handleWebhook(exchange, slackCount, slackBody));
        server.start();
        try {
            int port = server.getAddress().getPort();
            WebhookApprovalEscalationNotificationDispatcher dispatcher =
                    new WebhookApprovalEscalationNotificationDispatcher(new JsonUtil(new ObjectMapper()));
            ReflectionTestUtils.setField(dispatcher, "dispatchEnabled", true);
            ReflectionTestUtils.setField(dispatcher, "webhookUrl", "http://localhost:" + port + "/lark");
            ReflectionTestUtils.setField(dispatcher, "webhookUrls", "http://localhost:" + port + "/slack");
            ReflectionTestUtils.setField(dispatcher, "webhookChannelNames", "lark,slack");
            ReflectionTestUtils.setField(dispatcher, "webhookTimeoutMs", 3000L);

            dispatcher.dispatch(escalation());

            assertThat(dispatcher.isEnabled()).isTrue();
            assertThat(dispatcher.targetCount()).isEqualTo(2);
            assertThat(dispatcher.channelNames()).containsExactly("lark", "slack");
            assertThat(larkCount).hasValue(1);
            assertThat(slackCount).hasValue(1);
            assertThat(larkBody.get()).contains("\"dispatchChannel\":\"lark\"");
            assertThat(slackBody.get()).contains("\"dispatchChannel\":\"slack\"");
        } finally {
            server.stop(0);
        }
    }

    private void handleWebhook(HttpExchange exchange, AtomicInteger counter,
                               AtomicReference<String> bodyReference) throws IOException {
        counter.incrementAndGet();
        bodyReference.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private ExperimentApprovalEscalation escalation() {
        ExperimentApprovalEscalation escalation = new ExperimentApprovalEscalation();
        escalation.setEscalationId("esc-fanout");
        escalation.setExperimentId("exp-a");
        escalation.setApprovalType(ExperimentApprovalTaskType.EXPERIMENT_START);
        escalation.setDraftVersion(0L);
        escalation.setAppId("app-a");
        escalation.setOwner("owner-a");
        escalation.setExperimentName("多通道告警实验");
        escalation.setApprovalSubmittedAt(LocalDateTime.now().minusHours(6));
        escalation.setApprovalElapsedHours(6L);
        escalation.setApprovalSlaHours(4);
        escalation.setApprovalSlaStatus("OVERDUE");
        escalation.setEscalationOwners(List.of("owner-a"));
        escalation.setEscalationReason("审批超时");
        escalation.setNotificationPayload(Map.of("messageType", "APPROVAL_ESCALATION"));
        return escalation;
    }
}
