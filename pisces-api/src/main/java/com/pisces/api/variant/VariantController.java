package com.pisces.api.variant;

import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.VariantGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 变体生成控制器（无用户系统版本）
 * AI赋能：使用生成式AI批量产出高质量实验变体
 */
@RestController
@RequestMapping("/variants")
@NoTokenRequired  // 无需Token认证
public class VariantController {
    
    @Autowired
    private VariantGenerationService variantGenerationService;
    
    /**
     * 生成文本类变体（商品标题、详情页文案、咨询话术等）
     * AI赋能：使用生成式AI根据结构化Prompt批量生成文本变体
     */
    @PostMapping("/text/generate")
    public BaseResponse<List<String>> generateTextVariants(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "10") int count) {
        List<String> variants = variantGenerationService.generateTextVariants(prompt, count);
        return BaseResponse.of("生成成功", variants);
    }
    
    /**
     * 生成图像类变体（商品主图、详情页配图等）
     * AI赋能：使用图像生成AI根据Prompt生成图像变体
     */
    @PostMapping("/image/generate")
    public BaseResponse<List<String>> generateImageVariants(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "5") int count) {
        List<String> imageUrls = variantGenerationService.generateImageVariants(prompt, count);
        return BaseResponse.of("生成成功", imageUrls);
    }
    
    /**
     * 智能筛选变体（二级筛选机制）
     * AI赋能：通过规则过滤+算法预评估，筛选出高潜力变体
     */
    @PostMapping("/filter")
    public BaseResponse<List<String>> filterVariants(
            @RequestBody List<String> variants,
            @RequestParam String variantType) {
        VariantGenerationService.VariantType type = 
                VariantGenerationService.VariantType.valueOf(variantType.toUpperCase());
        List<String> filtered = variantGenerationService.filterVariants(variants, type);
        return BaseResponse.of("筛选完成", filtered);
    }
    
    /**
     * 评估变体质量（效果预评估）
     * AI赋能：使用变体效果预测模型，评估变体的优化潜力
     */
    @PostMapping("/evaluate")
    public BaseResponse<Map<String, Object>> evaluateVariant(
            @RequestParam String variant,
            @RequestParam String variantType) {
        VariantGenerationService.VariantType type = 
                VariantGenerationService.VariantType.valueOf(variantType.toUpperCase());
        Map<String, Object> evaluation = variantGenerationService.evaluateVariant(variant, type);
        return BaseResponse.of(evaluation);
    }
}
