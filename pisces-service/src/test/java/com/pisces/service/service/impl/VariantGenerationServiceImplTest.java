package com.pisces.service.service.impl;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.pisces.service.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantGenerationServiceImplTest {

    private final VariantGenerationServiceImpl service = new VariantGenerationServiceImpl();

    @Test
    void isDashScopeDirectImageUrlShouldRejectDataUrl() {
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isDashScopeDirectImageUrl",
                "data:image/png;base64,abc123"
        );

        assertThat(result).isFalse();
    }

    @Test
    void isDashScopeDirectImageUrlShouldAcceptOssUrl() {
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(
                service,
                "isDashScopeDirectImageUrl",
                "oss://pisces/example.png"
        );

        assertThat(result).isTrue();
    }

    @Test
    void parseImagePayloadShouldPreserveMimeTypeFromDataUrl() {
        String encoded = Base64.getEncoder().encodeToString("jpeg".getBytes(StandardCharsets.UTF_8));

        Object payload = ReflectionTestUtils.invokeMethod(
                service,
                "parseImagePayload",
                "data:image/jpeg;base64," + encoded
        );

        assertThat(ReflectionTestUtils.getField(payload, "extension")).isEqualTo(".jpg");
        assertThat((byte[]) ReflectionTestUtils.getField(payload, "bytes"))
                .isEqualTo("jpeg".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parseImagePayloadShouldDefaultRawBase64ToPng() {
        String encoded = Base64.getEncoder().encodeToString("png".getBytes(StandardCharsets.UTF_8));

        Object payload = ReflectionTestUtils.invokeMethod(service, "parseImagePayload", encoded);

        assertThat(ReflectionTestUtils.getField(payload, "extension")).isEqualTo(".png");
        assertThat((byte[]) ReflectionTestUtils.getField(payload, "bytes"))
                .isEqualTo("png".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolveImageEditInputShouldKeepExistingDataUrl() {
        String dataUrl = "data:image/webp;base64,abc123==";

        String result = (String) ReflectionTestUtils.invokeMethod(service, "resolveImageEditInput", dataUrl);

        assertThat(result).isEqualTo(dataUrl);
    }

    @Test
    void resolveImageEditInputShouldPrefixRawBase64AsPng() {
        String encoded = Base64.getEncoder().encodeToString("png".getBytes(StandardCharsets.UTF_8));

        String result = (String) ReflectionTestUtils.invokeMethod(service, "resolveImageEditInput", encoded);

        assertThat(result).isEqualTo("data:image/png;base64," + encoded);
    }

    @Test
    void resolveSupportedStylePromptShouldMapFrenchBookStyle() {
        String result = (String) ReflectionTestUtils.invokeMethod(service, "resolveSupportedStylePrompt", "french-book");

        assertThat(result).isEqualTo("转换成法国绘本风格");
    }

    @Test
    void resolveSupportedStylePromptShouldMapGoldFoilStyle() {
        String result = (String) ReflectionTestUtils.invokeMethod(service, "resolveSupportedStylePrompt", "gold-foil");

        assertThat(result).isEqualTo("转换成金箔艺术风格");
    }

    @Test
    void resolveSupportedStylePromptShouldRejectUnsupportedStyle() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "resolveSupportedStylePrompt", "watercolor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前仅支持");
    }

    @Test
    void normalizeImageVariantPromptShouldWrapUserInstruction() {
        String result = (String) ReflectionTestUtils.invokeMethod(
                service,
                "normalizeImageVariantPrompt",
                "将其调整为黑白风格"
        );

        assertThat(result).contains("请对输入图片执行严格编辑");
        assertThat(result).contains("将其调整为黑白风格");
        assertThat(result).contains("必须明显体现以下要求");
    }

    @Test
    void normalizeImageVariantPromptShouldRejectBlankPrompt() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "normalizeImageVariantPrompt",
                "   "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图生图提示词不能为空");
    }

    @Test
    void parseImagePayloadShouldRejectUnsupportedHeicMimeType() {
        String encoded = Base64.getEncoder().encodeToString("heic".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "parseImagePayload",
                "data:image/heic;base64," + encoded
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的图片格式");
    }

    @Test
    void assertImageSynthesisSucceededShouldPassWhenSucceededWithResults() {
        ImageSynthesisResult result = buildResult(
                "SUCCEEDED",
                null,
                null,
                List.of(Map.of("url", "https://example.com/result.png"))
        );

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(
                service,
                "assertImageSynthesisSucceeded",
                result,
                "通义图片编辑"
        )).doesNotThrowAnyException();
    }

    @Test
    void assertImageSynthesisSucceededShouldExposeFailedTaskReason() {
        ImageSynthesisResult result = buildResult(
                "FAILED",
                "InvalidParameter",
                "image size invalid",
                List.of()
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "assertImageSynthesisSucceeded",
                result,
                "通义图片编辑"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("taskStatus=FAILED")
                .hasMessageContaining("code=InvalidParameter")
                .hasMessageContaining("message=image size invalid");
    }

    @Test
    void normalizeReferenceImagePayloadShouldConvertJpegToPng() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);

        Object payload = ReflectionTestUtils.invokeMethod(
                service,
                "parseImagePayload",
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray())
        );

        Object normalized = ReflectionTestUtils.invokeMethod(service, "normalizeReferenceImagePayload", payload);

        assertThat(ReflectionTestUtils.getField(normalized, "extension")).isEqualTo(".png");
        byte[] bytes = (byte[]) ReflectionTestUtils.getField(normalized, "bytes");
        assertThat(bytes).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    @Test
    void normalizeReferenceImagePayloadShouldRejectWebpReferenceImage() {
        String encoded = Base64.getEncoder().encodeToString("webp".getBytes(StandardCharsets.UTF_8));
        Object payload = ReflectionTestUtils.invokeMethod(
                service,
                "parseImagePayload",
                "data:image/webp;base64," + encoded
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "normalizeReferenceImagePayload", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图生图参考图仅支持 JPEG、PNG");
    }

    @Test
    void createReferenceImageFileShouldProduceFileSchemePng() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);

        Object prepared = ReflectionTestUtils.invokeMethod(
                service,
                "createReferenceImageFile",
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray())
        );

        String input = (String) ReflectionTestUtils.getField(prepared, "input");
        Path tempFile = (Path) ReflectionTestUtils.getField(prepared, "tempFile");

        assertThat(input).startsWith("file://").endsWith(".png");
        assertThat(tempFile).isNotNull();
        assertThat(Files.exists(tempFile)).isTrue();
        assertThat(Files.readAllBytes(tempFile)).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);

        Files.deleteIfExists(tempFile);
    }

    @Test
    void extractImageUrlsShouldSupportAlternateUrlKeys() {
        ImageSynthesisResult result = buildResult(
                "SUCCEEDED",
                null,
                null,
                List.of(Map.of("result_url", "https://example.com/result.png"))
        );

        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) ReflectionTestUtils.invokeMethod(service, "extractImageUrls", result);

        assertThat(urls).containsExactly("https://example.com/result.png");
    }

    private ImageSynthesisResult buildResult(String taskStatus,
                                             String code,
                                             String message,
                                             List<Map<String, String>> results) {
        ImageSynthesisOutput output = new ImageSynthesisOutput();
        output.setTaskId("task-1");
        output.setTaskStatus(taskStatus);
        output.setCode(code);
        output.setMessage(message);
        output.setResults(results);

        ImageSynthesisResult result = newImageSynthesisResult();
        result.setOutput(output);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    private ImageSynthesisResult newImageSynthesisResult() {
        try {
            Constructor<ImageSynthesisResult> constructor = ImageSynthesisResult.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("无法构造 ImageSynthesisResult 测试对象", e);
        }
    }
}
