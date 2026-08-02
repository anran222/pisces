package com.pisces.service.ai;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通义文本生成客户端测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 15:03
 */
class TongYiTextGenerationClientTest {

    @Test
    void shouldRejectWhenTongYiDisabled() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(false);
        TongYiTextGenerationClient client = new TongYiTextGenerationClient(tongYiConfig);

        assertThatThrownBy(() -> client.generateText("system", "user", "AI诊断"))
                .isInstanceOf(BusinessException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldRejectWhenApiKeyMissing() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey(" ");
        TongYiTextGenerationClient client = new TongYiTextGenerationClient(tongYiConfig);

        assertThatThrownBy(() -> client.generateText("system", "user", "AI诊断"))
                .isInstanceOf(BusinessException.class)
                .extracting("responseCode")
                .isEqualTo(ResponseCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldRejectWhenConfiguredModelIsVisionModel() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        tongYiConfig.setModel("qwen3-vl-plus");
        TongYiTextGenerationClient client = new TongYiTextGenerationClient(tongYiConfig);

        assertThatThrownBy(() -> client.generateText("system", "user", "AI诊断"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前模型不适用于纯文本 Generation 接口")
                .extracting("responseCode")
                .isEqualTo(ResponseCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldCreateGenerationWithExplicitDashScopeHttpBaseUrl() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        TongYiTextGenerationClient client = new TongYiTextGenerationClient(tongYiConfig);

        Object generation = ReflectionTestUtils.invokeMethod(client, "createGeneration");
        Object serviceOption = ReflectionTestUtils.getField(generation, "serviceOption");
        Object baseHttpUrl = ReflectionTestUtils.invokeMethod(serviceOption, "getBaseHttpUrl");

        assertThat(baseHttpUrl).isEqualTo("https://dashscope.aliyuncs.com/api/v1");
    }

    @Test
    void shouldUseLatestProductionDashScopeModelByDefault() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        CapturingTongYiTextGenerationClient client = new CapturingTongYiTextGenerationClient(tongYiConfig);
        client.dashScopeResult = newGenerationResultWithText("生产模型返回");

        String text = client.generateText("system", "user", "AI诊断");

        assertThat(text).isEqualTo("生产模型返回");
        assertThat(client.dashScopeModel).isEqualTo("qwen3.7-max");
        assertThat(client.dashScopeBaseUrl).isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        assertThat(client.openAiModel).isNull();
        assertThat(client.getLastInvocationSummary())
                .extracting(
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::primaryModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedApiMode,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::fallbackUsed)
                .containsExactly("qwen3.7-max", "qwen3.7-max", "dashscope", false);
    }

    @Test
    void shouldUseTokenPlanPreviewModelWhenExplicitlyConfigured() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        tongYiConfig.setModel(TongYiConfig.PREVIEW_TEXT_MODEL);
        tongYiConfig.setApiMode(TongYiConfig.OPENAI_COMPATIBLE_API_MODE);
        tongYiConfig.setBaseUrl(TongYiConfig.TOKEN_PLAN_COMPATIBLE_BASE_URL);
        CapturingTongYiTextGenerationClient client = new CapturingTongYiTextGenerationClient(tongYiConfig);
        client.openAiResponseBody = """
                {"choices":[{"message":{"content":"预览模型返回"}}]}
                """;

        String text = client.generateText("system", "user", "AI诊断");

        assertThat(text).isEqualTo("预览模型返回");
        assertThat(client.openAiModel).isEqualTo("qwen3.8-max-preview");
        assertThat(client.openAiBaseUrl).isEqualTo("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        assertThat(client.dashScopeCalled).isFalse();
        assertThat(client.getLastInvocationSummary())
                .extracting(
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::primaryModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedApiMode,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::fallbackUsed)
                .containsExactly("qwen3.8-max-preview", "qwen3.8-max-preview", "openai-compatible", false);
    }

    @Test
    void shouldFallbackToProductionDashScopeModelWhenPreviewModelFails() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        tongYiConfig.setModel(TongYiConfig.PREVIEW_TEXT_MODEL);
        tongYiConfig.setApiMode(TongYiConfig.OPENAI_COMPATIBLE_API_MODE);
        tongYiConfig.setBaseUrl(TongYiConfig.TOKEN_PLAN_COMPATIBLE_BASE_URL);
        CapturingTongYiTextGenerationClient client = new CapturingTongYiTextGenerationClient(tongYiConfig);
        client.openAiException = new IllegalStateException("preview access denied");
        client.dashScopeResult = newGenerationResultWithText("稳定模型返回");

        String text = client.generateText("system", "user", "AI诊断");

        assertThat(text).isEqualTo("稳定模型返回");
        assertThat(client.openAiModel).isEqualTo("qwen3.8-max-preview");
        assertThat(client.dashScopeModel).isEqualTo("qwen3.7-max");
        assertThat(client.dashScopeBaseUrl).isEqualTo("https://dashscope.aliyuncs.com/api/v1");
        assertThat(client.getLastInvocationSummary())
                .extracting(
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::primaryModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedModel,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::selectedApiMode,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::fallbackUsed,
                        TongYiTextGenerationClient.TextGenerationInvocationSummary::attemptedModels)
                .containsExactly(
                        "qwen3.8-max-preview",
                        "qwen3.7-max",
                        "dashscope",
                        true,
                        java.util.List.of("qwen3.8-max-preview", "qwen3.7-max"));
    }

    @Test
    void shouldExtractTextFromMessageChoiceOutput() {
        TongYiConfig tongYiConfig = new TongYiConfig();
        tongYiConfig.setEnabled(true);
        tongYiConfig.setApiKey("test-api-key");
        TongYiTextGenerationClient client = new TongYiTextGenerationClient(tongYiConfig);

        GenerationOutput output = new GenerationOutput();
        GenerationOutput.Choice choice = newGenerationChoice(output);
        Message message = new Message();
        message.setContent("生成的标题内容");
        choice.setMessage(message);
        output.setChoices(java.util.List.of(choice));

        GenerationResult result = newGenerationResult();
        result.setOutput(output);

        String text = ReflectionTestUtils.invokeMethod(client, "extractText", result, "通义文本生成");

        assertThat(text).isEqualTo("生成的标题内容");
    }

    private GenerationResult newGenerationResultWithText(String text) {
        GenerationOutput output = new GenerationOutput();
        output.setText(text);

        GenerationResult result = newGenerationResult();
        result.setOutput(output);
        return result;
    }

    private GenerationResult newGenerationResult() {
        try {
            Constructor<GenerationResult> constructor = GenerationResult.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception exception) {
            throw new IllegalStateException("无法构造 GenerationResult 测试对象", exception);
        }
    }

    private GenerationOutput.Choice newGenerationChoice(GenerationOutput output) {
        try {
            Constructor<GenerationOutput.Choice> constructor = GenerationOutput.Choice.class
                    .getDeclaredConstructor(GenerationOutput.class);
            constructor.setAccessible(true);
            return constructor.newInstance(output);
        } catch (Exception exception) {
            throw new IllegalStateException("无法构造 GenerationOutput.Choice 测试对象", exception);
        }
    }

    private static class CapturingTongYiTextGenerationClient extends TongYiTextGenerationClient {

        private String openAiResponseBody;
        private Exception openAiException;
        private String openAiModel;
        private String openAiBaseUrl;
        private GenerationResult dashScopeResult;
        private boolean dashScopeCalled;
        private String dashScopeModel;
        private String dashScopeBaseUrl;

        private CapturingTongYiTextGenerationClient(TongYiConfig tongYiConfig) {
            super(tongYiConfig);
        }

        @Override
        String executeOpenAiCompatible(
                String model, String baseUrl, String systemPrompt, String userPrompt) throws Exception {
            this.openAiModel = model;
            this.openAiBaseUrl = baseUrl;
            if (openAiException != null) {
                throw openAiException;
            }
            return openAiResponseBody;
        }

        @Override
        GenerationResult executeGeneration(GenerationParam param, String baseHttpUrl) {
            this.dashScopeCalled = true;
            this.dashScopeModel = param.getModel();
            this.dashScopeBaseUrl = baseHttpUrl;
            return dashScopeResult;
        }
    }
}
