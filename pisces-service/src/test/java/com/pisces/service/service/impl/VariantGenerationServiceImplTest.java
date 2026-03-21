package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VariantGenerationServiceImplTest {

    private final TongYiConfig tongYiConfig = createTongYiConfig();
    private final TongYiTextGenerationClient tongYiTextGenerationClient = mock(TongYiTextGenerationClient.class);
    private final VariantGenerationServiceImpl service = new VariantGenerationServiceImpl(
            tongYiConfig,
            new JsonUtil(new ObjectMapper()),
            tongYiTextGenerationClient
    );

    @Test
    void shouldBuildStructuredPromptWithRequestedCount() {
        String prompt = (String) ReflectionTestUtils.invokeMethod(service, "buildStructuredPrompt", "写一个商品标题", 3);

        assertThat(prompt).contains("生成3个不同的文案变体");
        assertThat(prompt).contains("写一个商品标题");
    }

    @Test
    void shouldBuildImagePromptWithCommercialHints() {
        String prompt = (String) ReflectionTestUtils.invokeMethod(service, "buildImagePrompt", "二手手机主图");

        assertThat(prompt).contains("二手手机主图");
        assertThat(prompt).contains("专业摄影");
        assertThat(prompt).contains("白色背景");
    }

    @Test
    void shouldBuildWanTextToImageRequestBodyWithoutReferenceImage() {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageRequestBody",
                "生成二手手机主图",
                3,
                Map.of()
        );

        assertThat(requestBody.get("model")).isEqualTo("wan2.6-t2i");
        assertThat(requestBody)
                .extractingByKey("input")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("messages")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
        assertThat(requestBody)
                .extractingByKey("parameters")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("n", 3)
                .containsEntry("size", "1280*1280")
                .containsEntry("prompt_extend", true)
                .containsEntry("watermark", false);
    }

    @Test
    void shouldBuildWanImageEditRequestBodyWhenReferenceImageProvided() {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageRequestBody",
                "把参考图改成更适合二手手机售卖页的主图",
                2,
                Map.of("imageUrl", "https://example.com/source.png")
        );

        assertThat(requestBody.get("model")).isEqualTo("wan2.6-image");
        assertThat(requestBody)
                .extractingByKey("parameters")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("enable_interleave", false)
                .containsEntry("n", 2)
                .containsEntry("size", "1K");

        assertThat(requestBody)
                .extractingByKey("input")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("messages")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .first()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("content")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .extracting(item -> ((Map<String, Object>) item).get("image"))
                .contains("https://example.com/source.png");
    }

    @Test
    void shouldExtractImageUrlsFromAsyncOutput() {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "extractImageUrls",
                Map.of("results", List.of(
                        Map.of("url", "https://example.com/a.png"),
                        Map.of("url", "https://example.com/b.png")
                ))
        );

        assertThat(urls).containsExactly("https://example.com/a.png", "https://example.com/b.png");
    }

    @Test
    void shouldExtractImageUrlsFromAssistantChoicesContent() {
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) ReflectionTestUtils.invokeMethod(
                service,
                "extractImageUrls",
                Map.of("choices", List.of(
                        Map.of("message", Map.of(
                                "content", List.of(
                                        Map.of("type", "text", "text", "说明"),
                                        Map.of("type", "image", "image", "https://example.com/c.png"),
                                        Map.of("type", "image", "image", "https://example.com/d.png")
                                )
                        ))
                ))
        );

        assertThat(urls).containsExactly("https://example.com/c.png", "https://example.com/d.png");
    }

    @Test
    void shouldParseTextVariantsFromSharedTongYiClient() {
        when(tongYiTextGenerationClient.generateText(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("""
                        iPhone 16 Pro 九五新成色到手即用
                        iPhone 16 Pro 品质机况透明价格实在
                        iPhone 16 Pro 成色优秀支持平台质检
                        iPhone 16 Pro 高品质二手现货放心选
                        """);

        List<String> variants = service.generateTextVariants("生成二手手机标题", 4);

        assertThat(variants).containsExactly(
                "iPhone 16 Pro 九五新成色到手即用",
                "iPhone 16 Pro 品质机况透明价格实在",
                "iPhone 16 Pro 成色优秀支持平台质检",
                "iPhone 16 Pro 高品质二手现货放心选"
        );
    }

    private TongYiConfig createTongYiConfig() {
        TongYiConfig config = new TongYiConfig();
        config.setEnabled(true);
        config.setApiKey("test-api-key");
        return config;
    }

    @Test
    void shouldNotKeepLegacyImageToolMethods() {
        List<String> methodNames = Arrays.stream(VariantGenerationServiceImpl.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .doesNotContain("generateImageVariantsFromImage")
                .doesNotContain("editImage")
                .doesNotContain("transferImageStyle")
                .doesNotContain("getStylePrompt")
                .doesNotContain("resolveSupportedStylePrompt")
                .doesNotContain("generateCompleteTextExperiment")
                .doesNotContain("generateCompleteExperimentFlow")
                .doesNotContain("filterVariants")
                .doesNotContain("filterByRules")
                .doesNotContain("filterByAlgorithm")
                .doesNotContain("evaluateVariant")
                .doesNotContain("callTongYiTextApi");
    }
}
