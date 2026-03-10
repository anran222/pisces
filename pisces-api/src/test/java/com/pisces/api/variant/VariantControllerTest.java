package com.pisces.api.variant;

import com.pisces.common.response.BaseResponse;
import com.pisces.service.service.VariantGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantControllerTest {

    @Test
    void transferImageStyleShouldPreserveDataUrlMimePrefix() {
        VariantGenerationService service = mock(VariantGenerationService.class);
        VariantController controller = new VariantController();
        ReflectionTestUtils.setField(controller, "variantGenerationService", service);

        String imageBase64 = "data:image/webp;base64,abc123";
        String resultUrl = "https://example.com/result.png";
        when(service.transferImageStyle(imageBase64, "cartoon")).thenReturn(resultUrl);

        Map<String, Object> request = new HashMap<>();
        request.put("imageBase64", imageBase64);
        request.put("style", "cartoon");

        BaseResponse<Map<String, String>> response = controller.transferImageStyle(request);

        verify(service).transferImageStyle(eq(imageBase64), eq("cartoon"));
        assertThat(response.getData())
                .containsEntry("resultImageUrl", resultUrl)
                .containsEntry("style", "cartoon")
                .containsEntry("downloadFileName", "style-cartoon.png");
        assertThat(response.getData().get("downloadUrl"))
                .startsWith("/api/variants/image/download")
                .contains("/variants/image/download?url=")
                .contains("fileName=style-cartoon.png");
    }

    @Test
    void getImageStylesShouldExposeOnlySupportedOfficialStyles() {
        VariantController controller = new VariantController();

        BaseResponse<List<Map<String, String>>> response = controller.getImageStyles();

        assertThat(response.getData())
                .extracting(item -> item.get("id"))
                .containsExactly("french-book", "gold-foil");
    }

    @Test
    void editImageShouldPreserveBaseAndMaskDataUrlMimePrefix() {
        VariantGenerationService service = mock(VariantGenerationService.class);
        VariantController controller = new VariantController();
        ReflectionTestUtils.setField(controller, "variantGenerationService", service);

        String imageBase64 = "data:image/png;base64,base-image";
        String maskBase64 = "data:image/png;base64,mask-image";
        when(service.editImage(imageBase64, maskBase64, "优化图片")).thenReturn("https://example.com/result.png");

        Map<String, Object> request = new HashMap<>();
        request.put("imageBase64", imageBase64);
        request.put("maskBase64", maskBase64);
        request.put("prompt", "优化图片");

        BaseResponse<String> response = controller.editImage(request);

        verify(service).editImage(eq(imageBase64), eq(maskBase64), eq("优化图片"));
        assertThat(response.getData()).isEqualTo("https://example.com/result.png");
    }

    @Test
    void generateFromImageShouldPreserveDataUrlMimePrefix() {
        VariantGenerationService service = mock(VariantGenerationService.class);
        VariantController controller = new VariantController();
        ReflectionTestUtils.setField(controller, "variantGenerationService", service);

        String imageBase64 = "data:image/jpeg;base64,abc123";
        when(service.generateImageVariantsFromImage(imageBase64, "优化图片效果", 4))
                .thenReturn(List.of("https://example.com/1.png"));

        Map<String, Object> request = new HashMap<>();
        request.put("imageBase64", imageBase64);

        BaseResponse<List<String>> response = controller.generateImageVariantsFromImage(request);

        verify(service).generateImageVariantsFromImage(eq(imageBase64), eq("优化图片效果"), eq(4));
        assertThat(response.getData()).containsExactly("https://example.com/1.png");
    }
}
