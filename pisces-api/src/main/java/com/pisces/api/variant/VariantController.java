package com.pisces.api.variant;

import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.VariantGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 变体生成控制器（无用户系统版本）
 * AI赋能：使用生成式AI批量产出高质量实验候选变体
 */
@RestController
@RequestMapping("/variants")
@NoTokenRequired
@RequiredArgsConstructor
public class VariantController {

    private static final String TEXT_GENERATE_SUCCESS_MESSAGE = "生成成功";

    private static final String IMAGE_GENERATE_SUCCESS_MESSAGE = "生成成功";

    private final VariantGenerationService variantGenerationService;

    /**
     * 生成文本类候选变体（商品标题、详情页文案、咨询话术等）
     */
    @PostMapping("/text/generate")
    public BaseResponse<List<String>> generateTextVariants(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "count", defaultValue = "10") int count) {
        List<String> variants = variantGenerationService.generateTextVariants(prompt, count);
        return BaseResponse.of(TEXT_GENERATE_SUCCESS_MESSAGE, variants);
    }

    /**
     * 生成图像类候选变体（商品主图、详情页配图等）
     */
    @PostMapping("/image/generate")
    public BaseResponse<List<String>> generateImageVariants(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "count", defaultValue = "5") int count) {
        List<String> imageUrls = variantGenerationService.generateImageVariants(prompt, count);
        return BaseResponse.of(IMAGE_GENERATE_SUCCESS_MESSAGE, imageUrls);
    }
}
