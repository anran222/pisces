package com.pisces.service.ai;

import com.alibaba.dashscope.aigc.generation.GenerationOutput;
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
}
