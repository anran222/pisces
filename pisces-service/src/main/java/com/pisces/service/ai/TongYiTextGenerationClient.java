package com.pisces.service.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通义文本生成客户端
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 15:03
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TongYiTextGenerationClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String MULTIMODAL_MODEL_ERROR_MESSAGE = "当前模型不适用于纯文本 Generation 接口，请切换到纯文本模型";
    private static final String EMPTY_RESULT_MESSAGE = "%s失败: 通义返回空结果";
    private static final String EMPTY_OUTPUT_MESSAGE = "%s失败: 通义返回缺少 output";
    private static final String EMPTY_TEXT_MESSAGE = "%s失败: 通义未返回有效文本";
    private static final String FAILURE_MESSAGE = "%s失败: %s";
    private static final int HTTP_BODY_PREVIEW_MAX_LENGTH = 500;
    private static final int LOG_PREVIEW_MAX_LENGTH = 160;

    private final TongYiConfig tongYiConfig;
    private final ThreadLocal<TextGenerationInvocationSummary> lastInvocationSummary = new ThreadLocal<>();

    public String generateText(String systemPrompt, String userPrompt, String operationName) {
        lastInvocationSummary.remove();
        List<TextModelInvocation> invocations = validateConfigAndResolveTextModelInvocations();

        Exception lastException = null;
        for (int index = 0; index < invocations.size(); index++) {
            TextModelInvocation invocation = invocations.get(index);
            try {
                log.info("发起通义文本生成: operation={}, model={}, mode={}, promptLength={}, promptPreview={}",
                        operationName,
                        invocation.model(),
                        invocation.apiMode(),
                        userPrompt == null ? 0 : userPrompt.length(),
                        summarizeForLog(userPrompt));
                String text = executeTextGeneration(invocation, systemPrompt, userPrompt, operationName);
                recordSuccessfulInvocation(invocations, index);
                log.info("通义文本生成完成: operation={}, model={}, mode={}, preview={}",
                        operationName, invocation.model(), invocation.apiMode(), summarizeForLog(text));
                return text;
            } catch (BusinessException exception) {
                lastException = exception;
                if (index + 1 >= invocations.size()) {
                    log.error("通义文本生成业务异常: operation={}, model={}, mode={}, message={}",
                            operationName, invocation.model(), invocation.apiMode(), exception.getMessage(), exception);
                    throw exception;
                }
                log.warn("通义文本生成候选模型失败，准备尝试回退: operation={}, model={}, mode={}, nextModel={}",
                        operationName,
                        invocation.model(),
                        invocation.apiMode(),
                        invocations.get(index + 1).model());
            } catch (Exception exception) {
                lastException = exception;
                if (index + 1 >= invocations.size()) {
                    log.error("通义文本生成调用失败: operation={}, model={}, mode={}",
                            operationName, invocation.model(), invocation.apiMode(), exception);
                    throw toBusinessException(operationName, exception);
                }
                log.warn("通义文本生成候选模型调用失败，准备尝试回退: operation={}, model={}, mode={}, nextModel={}, error={}",
                        operationName,
                        invocation.model(),
                        invocation.apiMode(),
                        invocations.get(index + 1).model(),
                        exception.getMessage());
            }
        }
        throw toBusinessException(operationName, lastException);
    }

    public TextGenerationInvocationSummary getLastInvocationSummary() {
        return lastInvocationSummary.get();
    }

    private String executeTextGeneration(
            TextModelInvocation invocation, String systemPrompt, String userPrompt, String operationName)
            throws Exception {
        if (TongYiConfig.OPENAI_COMPATIBLE_API_MODE.equals(invocation.apiMode())) {
            return extractOpenAiCompatibleText(
                    executeOpenAiCompatible(invocation.model(), invocation.baseUrl(), systemPrompt, userPrompt),
                    operationName);
        }
        if (TongYiConfig.DASHSCOPE_API_MODE.equals(invocation.apiMode())) {
            GenerationResult result = executeGeneration(GenerationParam.builder()
                    .apiKey(tongYiConfig.getApiKey())
                    .model(invocation.model())
                    .messages(List.of(
                            createMessage(Role.SYSTEM.getValue(), systemPrompt),
                            createMessage(Role.USER.getValue(), userPrompt)))
                    .resultFormat("message")
                    .build(), invocation.baseUrl());
            return extractText(result, operationName);
        }
        throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE,
                "不支持的通义文本调用协议: " + invocation.apiMode());
    }

    Generation createGeneration() {
        return createGeneration(TongYiConfig.DASHSCOPE_HTTP_BASE_URL);
    }

    Generation createGeneration(String baseHttpUrl) {
        return new Generation(Protocol.HTTP.getValue(),
                normalizeBaseUrl(baseHttpUrl, TongYiConfig.DASHSCOPE_HTTP_BASE_URL));
    }

    GenerationResult executeGeneration(GenerationParam param) throws Exception {
        return createGeneration().call(param);
    }

    GenerationResult executeGeneration(GenerationParam param, String baseHttpUrl) throws Exception {
        return createGeneration(baseHttpUrl).call(param);
    }

    String executeOpenAiCompatible(
            String model, String baseUrl, String systemPrompt, String userPrompt) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(
                createOpenAiCompatibleMessage(Role.SYSTEM.getValue(), systemPrompt),
                createOpenAiCompatibleMessage(Role.USER.getValue(), userPrompt)));
        payload.put("stream", false);
        if (tongYiConfig.isEnableThinking()) {
            payload.put("enable_thinking", true);
        }

        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri(baseUrl))
                .timeout(Duration.ofMillis(Math.max(tongYiConfig.getTimeout(), 1)))
                .header("Authorization", "Bearer " + tongYiConfig.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                    + summarizeHttpBody(response.body()));
        }
        return response.body();
    }

    private List<TextModelInvocation> validateConfigAndResolveTextModelInvocations() {
        if (!tongYiConfig.isEnabled()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI未启用，无法执行真实AI流程");
        }
        if (!StringUtils.hasText(tongYiConfig.getApiKey())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置 TONGYI_API_KEY，无法执行真实AI流程");
        }
        List<TextModelInvocation> invocations = resolveTextModelInvocations();
        if (invocations.isEmpty()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置可用的通义文本模型");
        }
        for (TextModelInvocation invocation : invocations) {
            if (isMultimodalOnlyModel(invocation.model())) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, MULTIMODAL_MODEL_ERROR_MESSAGE);
            }
        }
        return invocations;
    }

    private List<TextModelInvocation> resolveTextModelInvocations() {
        List<TextModelInvocation> invocations = new ArrayList<>();
        addInvocation(
                invocations,
                tongYiConfig.getModel(),
                tongYiConfig.getApiMode(),
                tongYiConfig.getBaseUrl(),
                TongYiConfig.OPENAI_COMPATIBLE_API_MODE,
                TongYiConfig.TOKEN_PLAN_COMPATIBLE_BASE_URL);
        if (tongYiConfig.isModelFallbackEnabled()) {
            addInvocation(
                    invocations,
                    tongYiConfig.getFallbackModel(),
                    tongYiConfig.getFallbackApiMode(),
                    tongYiConfig.getFallbackBaseUrl(),
                    TongYiConfig.DASHSCOPE_API_MODE,
                    TongYiConfig.DASHSCOPE_HTTP_BASE_URL);
        }
        return invocations;
    }

    private void addInvocation(
            List<TextModelInvocation> invocations,
            String model,
            String apiMode,
            String baseUrl,
            String defaultApiMode,
            String defaultBaseUrl) {
        if (!StringUtils.hasText(model)) {
            return;
        }
        TextModelInvocation invocation = new TextModelInvocation(
                model.trim(),
                normalizeApiMode(apiMode, defaultApiMode),
                normalizeBaseUrl(baseUrl, defaultBaseUrl));
        if (invocations.stream().noneMatch(existing -> existing.equals(invocation))) {
            invocations.add(invocation);
        }
    }

    private void recordSuccessfulInvocation(List<TextModelInvocation> invocations, int selectedIndex) {
        TextModelInvocation selectedInvocation = invocations.get(selectedIndex);
        List<String> attemptedModels = invocations.stream()
                .limit(selectedIndex + 1L)
                .map(TextModelInvocation::model)
                .toList();
        String configuredFallbackModel = invocations.size() > 1 ? invocations.get(1).model() : null;
        lastInvocationSummary.set(new TextGenerationInvocationSummary(
                invocations.get(0).model(),
                selectedInvocation.model(),
                selectedInvocation.apiMode(),
                selectedIndex > 0,
                configuredFallbackModel,
                attemptedModels
        ));
    }

    private Message createMessage(String role, String content) {
        return Message.builder()
                .role(role)
                .content(content)
                .build();
    }

    private Map<String, String> createOpenAiCompatibleMessage(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String extractText(GenerationResult result, String operationName) {
        if (result == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, EMPTY_RESULT_MESSAGE.formatted(operationName));
        }
        if (result.getOutput() == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, EMPTY_OUTPUT_MESSAGE.formatted(operationName));
        }
        String text = extractMessageText(result);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, EMPTY_TEXT_MESSAGE.formatted(operationName));
        }
        return text.trim();
    }

    private String extractMessageText(GenerationResult result) {
        if (StringUtils.hasText(result.getOutput().getText())) {
            return result.getOutput().getText();
        }
        if (result.getOutput().getChoices() == null || result.getOutput().getChoices().isEmpty()) {
            return null;
        }
        Message message = result.getOutput().getChoices().getFirst().getMessage();
        if (message == null) {
            return null;
        }
        return message.getContent();
    }

    private String extractOpenAiCompatibleText(String responseBody, String operationName) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, EMPTY_OUTPUT_MESSAGE.formatted(operationName));
        }
        JsonNode contentNode = choices.get(0).path("message").path("content");
        String text = extractJsonText(contentNode);
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, EMPTY_TEXT_MESSAGE.formatted(operationName));
        }
        return text.trim();
    }

    private String extractJsonText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            node.forEach(item -> {
                String itemText = extractJsonText(item.path("text"));
                if (!StringUtils.hasText(itemText)) {
                    itemText = extractJsonText(item.path("content"));
                }
                if (StringUtils.hasText(itemText)) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(itemText.trim());
                }
            });
            return builder.toString();
        }
        return null;
    }

    private boolean isMultimodalOnlyModel(String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        String normalizedModel = model.trim().toLowerCase();
        return normalizedModel.contains("vl")
                || normalizedModel.contains("omni");
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl, TongYiConfig.TOKEN_PLAN_COMPATIBLE_BASE_URL);
        if (normalizedBaseUrl.endsWith(OPENAI_CHAT_COMPLETIONS_PATH)) {
            return URI.create(normalizedBaseUrl);
        }
        return URI.create(normalizedBaseUrl + OPENAI_CHAT_COMPLETIONS_PATH);
    }

    private String normalizeApiMode(String apiMode, String defaultApiMode) {
        String mode = StringUtils.hasText(apiMode) ? apiMode : defaultApiMode;
        return mode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String normalizeBaseUrl(String baseUrl, String defaultBaseUrl) {
        String normalizedBaseUrl = StringUtils.hasText(baseUrl) ? baseUrl.trim() : defaultBaseUrl;
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        return normalizedBaseUrl;
    }

    private BusinessException toBusinessException(String operationName, Exception exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException;
        }
        String message = exception == null ? "未知错误" : exception.getMessage();
        return new BusinessException(ResponseCode.SERVICE_UNAVAILABLE,
                FAILURE_MESSAGE.formatted(operationName, message), exception);
    }

    private String summarizeHttpBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "<empty>";
        }
        String sanitizedBody = body;
        if (StringUtils.hasText(tongYiConfig.getApiKey())) {
            sanitizedBody = sanitizedBody.replace(tongYiConfig.getApiKey(), "<redacted>");
        }
        String normalizedBody = sanitizedBody.replaceAll("\\s+", " ").trim();
        if (normalizedBody.length() <= HTTP_BODY_PREVIEW_MAX_LENGTH) {
            return normalizedBody;
        }
        return normalizedBody.substring(0, HTTP_BODY_PREVIEW_MAX_LENGTH) + "...";
    }

    private String summarizeForLog(String text) {
        if (!StringUtils.hasText(text)) {
            return "<empty>";
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        if (normalizedText.length() <= LOG_PREVIEW_MAX_LENGTH) {
            return normalizedText;
        }
        return normalizedText.substring(0, LOG_PREVIEW_MAX_LENGTH) + "...";
    }

    private record TextModelInvocation(String model, String apiMode, String baseUrl) {
    }

    public record TextGenerationInvocationSummary(
            String primaryModel,
            String selectedModel,
            String selectedApiMode,
            boolean fallbackUsed,
            String fallbackModel,
            List<String> attemptedModels) {

        public Map<String, Object> toMetadataMap() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "tongyi");
            metadata.put("primaryModel", primaryModel);
            metadata.put("selectedModel", selectedModel);
            metadata.put("selectedApiMode", selectedApiMode);
            metadata.put("fallbackUsed", fallbackUsed);
            metadata.put("fallbackModel", fallbackModel);
            metadata.put("attemptedModels", List.copyOf(attemptedModels));
            metadata.put("modelStrategy", "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in");
            return metadata;
        }
    }
}
