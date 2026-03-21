package com.pisces.service.ai;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.protocol.Protocol;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

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

    private static final String DASHSCOPE_HTTP_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String MULTIMODAL_MODEL_ERROR_MESSAGE = "当前模型不适用于纯文本 Generation 接口，请切换到纯文本模型";
    private static final String EMPTY_RESULT_MESSAGE = "%s失败: 通义返回空结果";
    private static final String EMPTY_OUTPUT_MESSAGE = "%s失败: 通义返回缺少 output";
    private static final String EMPTY_TEXT_MESSAGE = "%s失败: 通义未返回有效文本";
    private static final String FAILURE_MESSAGE = "%s失败: %s";
    private static final int LOG_PREVIEW_MAX_LENGTH = 160;

    private final TongYiConfig tongYiConfig;

    public String generateText(String systemPrompt, String userPrompt, String operationName) {
        validateConfig();

        try {
            log.info("发起通义文本生成: operation={}, model={}, prompt = {}", operationName, tongYiConfig.getModel(), userPrompt);
            GenerationResult result = executeGeneration(GenerationParam.builder()
                    .apiKey(tongYiConfig.getApiKey())
                    .model(tongYiConfig.getModel())
                    .messages(List.of(
                            createMessage(Role.SYSTEM.getValue(), systemPrompt),
                            createMessage(Role.USER.getValue(), userPrompt)))
                    .resultFormat("message")
                    .build());
            String text = extractText(result, operationName);
            log.info("通义文本生成完成: operation={}, model={}, preview={}",
                    operationName, tongYiConfig.getModel(), summarizeForLog(text));
            return text;
        } catch (BusinessException exception) {
            log.error("通义文本生成业务异常: operation={}, model={}, message={}",
                    operationName, tongYiConfig.getModel(), exception.getMessage(), exception);
            throw exception;
        } catch (Exception exception) {
            log.error("通义文本生成调用失败: operation={}, model={}",
                    operationName, tongYiConfig.getModel(), exception);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE,
                    FAILURE_MESSAGE.formatted(operationName, exception.getMessage()), exception);
        }
    }

    Generation createGeneration() {
        return new Generation(Protocol.HTTP.getValue(), DASHSCOPE_HTTP_BASE_URL);
    }

    GenerationResult executeGeneration(GenerationParam param) throws Exception {
        return createGeneration().call(param);
    }

    private void validateConfig() {
        if (!tongYiConfig.isEnabled()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI未启用，无法执行真实AI流程");
        }
        if (!StringUtils.hasText(tongYiConfig.getApiKey())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置 TONGYI_API_KEY，无法执行真实AI流程");
        }
        if (isMultimodalOnlyModel(tongYiConfig.getModel())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, MULTIMODAL_MODEL_ERROR_MESSAGE);
        }
    }

    private Message createMessage(String role, String content) {
        return Message.builder()
                .role(role)
                .content(content)
                .build();
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

    private boolean isMultimodalOnlyModel(String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        String normalizedModel = model.trim().toLowerCase();
        return normalizedModel.contains("vl")
                || normalizedModel.contains("omni");
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
}
