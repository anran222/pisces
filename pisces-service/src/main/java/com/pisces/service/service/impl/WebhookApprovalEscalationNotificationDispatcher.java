package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import com.pisces.service.service.ApprovalEscalationNotificationTarget;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Webhook 审批升级告警投递器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookApprovalEscalationNotificationDispatcher implements ApprovalEscalationNotificationDispatcher {

    private static final int HTTP_SUCCESS_STATUS_MIN = 200;

    private static final int HTTP_SUCCESS_STATUS_MAX = 299;

    private static final String CONTENT_TYPE_JSON = "application/json";

    private static final String DEFAULT_WEBHOOK_CHANNEL = "WEBHOOK";

    private static final String TARGET_KEY_PREFIX = "webhook_";

    private static final int TARGET_KEY_HASH_LENGTH = 32;

    private final JsonUtil jsonUtil;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${pisces.approval-escalation.dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Value("${pisces.approval-escalation.webhook-url:}")
    private String webhookUrl;

    @Value("${pisces.approval-escalation.webhook-urls:}")
    private String webhookUrls;

    @Value("${pisces.approval-escalation.webhook-channel-names:}")
    private String webhookChannelNames;

    @Value("${pisces.approval-escalation.webhook-timeout-ms:3000}")
    private long webhookTimeoutMs;

    @Override
    public boolean isEnabled() {
        return dispatchEnabled && !resolveWebhookUrls().isEmpty();
    }

    @Override
    public int targetCount() {
        return targets().size();
    }

    @Override
    public List<String> channelNames() {
        return targets().stream()
                .map(ApprovalEscalationNotificationTarget::getChannelName)
                .toList();
    }

    @Override
    public List<ApprovalEscalationNotificationTarget> targets() {
        if (!isEnabled()) {
            return List.of();
        }
        List<String> targetUrls = resolveWebhookUrls();
        List<ApprovalEscalationNotificationTarget> targets = new ArrayList<>();
        for (int index = 0; index < targetUrls.size(); index++) {
            String targetUrl = targetUrls.get(index);
            String channelName = resolveWebhookChannelName(index, targetUrls.size());
            targets.add(new ApprovalEscalationNotificationTarget(
                    buildTargetKey(targetUrl), channelName, targetUrl));
        }
        return targets;
    }

    @Override
    public void dispatch(ExperimentApprovalEscalation escalation) {
        if (!isEnabled()) {
            throw new IllegalStateException("审批升级告警 Webhook 未配置");
        }
        List<String> dispatchErrors = new ArrayList<>();
        for (ApprovalEscalationNotificationTarget target : targets()) {
            try {
                dispatch(escalation, target);
            } catch (IllegalStateException exception) {
                dispatchErrors.add(target.getChannelName() + ": " + exception.getMessage());
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        if (!dispatchErrors.isEmpty()) {
            String errorMessage = "Webhook 投递失败: " + String.join("; ", dispatchErrors);
            log.warn("审批升级告警 Webhook 投递失败: escalationId={}, errors={}",
                    escalation.getEscalationId(), dispatchErrors);
            throw new IllegalStateException(errorMessage);
        }
    }

    @Override
    public void dispatch(ExperimentApprovalEscalation escalation, ApprovalEscalationNotificationTarget target) {
        if (!isEnabled()) {
            throw new IllegalStateException("审批升级告警 Webhook 未配置");
        }
        if (target == null || !StringUtils.hasText(target.getTargetEndpoint())) {
            throw new IllegalStateException("审批升级告警 Webhook 目标未配置");
        }
        String requestBody = jsonUtil.toJson(buildWebhookPayload(escalation, target.getChannelName()));
        dispatchToWebhook(target.getTargetEndpoint(), requestBody);
    }

    private void dispatchToWebhook(String targetUrl, String requestBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofMillis(webhookTimeoutMs))
                .header("Content-Type", CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode < HTTP_SUCCESS_STATUS_MIN || statusCode > HTTP_SUCCESS_STATUS_MAX) {
                throw new IllegalStateException("Webhook 返回非成功状态码: " + statusCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook 投递被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private Map<String, Object> buildWebhookPayload(ExperimentApprovalEscalation escalation, String channelName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("escalationId", escalation.getEscalationId());
        payload.put("experimentId", escalation.getExperimentId());
        payload.put("approvalType", escalation.getApprovalType());
        payload.put("draftVersion", escalation.getDraftVersion());
        payload.put("appId", escalation.getAppId());
        payload.put("owner", escalation.getOwner());
        payload.put("experimentName", escalation.getExperimentName());
        payload.put("escalationReason", escalation.getEscalationReason());
        payload.put("escalationOwners", escalation.getEscalationOwners());
        payload.put("dispatchChannel", channelName);
        payload.put("notificationPayload", escalation.getNotificationPayload());
        return payload;
    }

    private List<String> resolveWebhookUrls() {
        LinkedHashSet<String> normalizedUrls = new LinkedHashSet<>();
        addWebhookUrl(normalizedUrls, webhookUrl);
        for (String candidateUrl : splitConfiguredValues(webhookUrls)) {
            addWebhookUrl(normalizedUrls, candidateUrl);
        }
        return normalizedUrls.stream().toList();
    }

    private void addWebhookUrl(LinkedHashSet<String> normalizedUrls, String candidateUrl) {
        if (StringUtils.hasText(candidateUrl)) {
            normalizedUrls.add(candidateUrl.trim());
        }
    }

    private String resolveWebhookChannelName(int index, int targetCount) {
        String[] channelNames = splitConfiguredValues(webhookChannelNames);
        if (index < channelNames.length && StringUtils.hasText(channelNames[index])) {
            return channelNames[index].trim();
        }
        if (targetCount == 1) {
            return DEFAULT_WEBHOOK_CHANNEL;
        }
        return DEFAULT_WEBHOOK_CHANNEL + "_" + (index + 1);
    }

    private String[] splitConfiguredValues(String configuredValue) {
        if (!StringUtils.hasText(configuredValue)) {
            return new String[0];
        }
        return configuredValue.split(",");
    }

    private String buildTargetKey(String targetUrl) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(targetUrl.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(hashBytes);
            return TARGET_KEY_PREFIX + hash.substring(0, TARGET_KEY_HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成 Webhook 投递目标标识", exception);
        }
    }
}
