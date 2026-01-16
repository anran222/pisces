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
     * 基于上传图片生成变体（图生图）
     * AI赋能：上传原始图片，根据提示词生成多个变体图片
     * @param request 包含 imageBase64（图片Base64编码）、prompt（修改提示词）、count（生成数量）
     */
    @PostMapping("/image/generate-from-image")
    public BaseResponse<List<String>> generateImageVariantsFromImage(
            @RequestBody Map<String, Object> request) {
        String imageBase64 = (String) request.get("imageBase64");
        String prompt = (String) request.getOrDefault("prompt", "优化图片效果");
        int count = request.containsKey("count") ? ((Number) request.get("count")).intValue() : 4;
        
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return BaseResponse.error(com.pisces.common.enums.ResponseCode.BAD_REQUEST, "请上传图片");
        }
        
        // 移除可能的data:image前缀
        if (imageBase64.contains(",")) {
            imageBase64 = imageBase64.split(",")[1];
        }
        
        List<String> imageUrls = variantGenerationService.generateImageVariantsFromImage(
                imageBase64, prompt, count);
        return BaseResponse.of("图生图成功", imageUrls);
    }
    
    /**
     * 图片局部编辑
     * AI赋能：上传原图和遮罩，对遮罩区域进行编辑
     * @param request 包含 imageBase64、maskBase64（可选）、prompt
     */
    @PostMapping("/image/edit")
    public BaseResponse<String> editImage(@RequestBody Map<String, Object> request) {
        String imageBase64 = (String) request.get("imageBase64");
        String maskBase64 = (String) request.get("maskBase64");
        String prompt = (String) request.getOrDefault("prompt", "优化图片");
        
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return BaseResponse.error(com.pisces.common.enums.ResponseCode.BAD_REQUEST, "请上传图片");
        }
        
        // 移除可能的data:image前缀
        if (imageBase64.contains(",")) {
            imageBase64 = imageBase64.split(",")[1];
        }
        if (maskBase64 != null && maskBase64.contains(",")) {
            maskBase64 = maskBase64.split(",")[1];
        }
        
        String resultUrl = variantGenerationService.editImage(imageBase64, maskBase64, prompt);
        return BaseResponse.of("编辑成功", resultUrl);
    }
    
    /**
     * 图片风格转换
     * AI赋能：将上传的图片转换为指定风格
     * @param request 包含 imageBase64、style（风格：cartoon, oil-painting, sketch, anime, watercolor, pixel, 3d, minimalist）
     */
    @PostMapping("/image/style-transfer")
    public BaseResponse<String> transferImageStyle(@RequestBody Map<String, Object> request) {
        String imageBase64 = (String) request.get("imageBase64");
        String style = (String) request.getOrDefault("style", "cartoon");
        
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return BaseResponse.error(com.pisces.common.enums.ResponseCode.BAD_REQUEST, "请上传图片");
        }
        
        // 移除可能的data:image前缀
        if (imageBase64.contains(",")) {
            imageBase64 = imageBase64.split(",")[1];
        }
        
        String resultUrl = variantGenerationService.transferImageStyle(imageBase64, style);
        return BaseResponse.of("风格转换成功", resultUrl);
    }
    
    /**
     * 获取支持的图片风格列表
     */
    @GetMapping("/image/styles")
    public BaseResponse<List<Map<String, String>>> getImageStyles() {
        List<Map<String, String>> styles = new java.util.ArrayList<>();
        
        styles.add(Map.of("id", "cartoon", "name", "卡通风格", "description", "色彩鲜艳的卡通漫画效果"));
        styles.add(Map.of("id", "oil-painting", "name", "油画风格", "description", "厚重笔触的印象派油画"));
        styles.add(Map.of("id", "sketch", "name", "素描风格", "description", "黑白铅笔素描效果"));
        styles.add(Map.of("id", "anime", "name", "动漫风格", "description", "日本动漫风格"));
        styles.add(Map.of("id", "watercolor", "name", "水彩风格", "description", "柔和的水彩画效果"));
        styles.add(Map.of("id", "pixel", "name", "像素风格", "description", "8bit复古像素艺术"));
        styles.add(Map.of("id", "3d", "name", "3D风格", "description", "立体3D渲染效果"));
        styles.add(Map.of("id", "minimalist", "name", "极简风格", "description", "简洁线条的极简设计"));
        
        return BaseResponse.of(styles);
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
    
    /**
     * 完整文本实验体生成演示（生成+筛选+评估）
     * AI赋能：一站式生成高质量文本变体，自动完成生成、筛选、评估全流程
     * 
     * @param prompt 生成提示词
     * @param generateCount 初始生成数量（默认20）
     * @param finalCount 最终保留数量（默认5）
     * @return 完整的实验体结果，包含变体列表、评估结果、统计信息等
     */
    @PostMapping("/text/demo")
    public BaseResponse<Map<String, Object>> generateCompleteTextExperiment(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "20") int generateCount,
            @RequestParam(defaultValue = "5") int finalCount) {
        Map<String, Object> result = variantGenerationService.generateCompleteTextExperiment(
                prompt, generateCount, finalCount);
        return BaseResponse.of("演示生成成功", result);
    }
    
    /**
     * 完整实验流程演示（生成变体+创建实验+生成数据+分析）
     * AI赋能：一站式完成从变体生成到实验分析的完整流程
     * 以二手手机价格为例，完整演示整个A/B测试流程
     * 
     * @param prompt 生成提示词（如：为二手手机价格优化写文案）
     * @param generateCount 初始生成变体数量（默认15）
     * @param finalCount 最终保留变体数量，作为实验组（默认4）
     * @param visitorCount 每个实验组的访客数量（默认150）
     * @param daysAgo 实验开始时间，几天前（默认7）
     * @return 完整的实验流程结果，包含实验ID、变体信息、分析结果等
     */
    @PostMapping("/experiment/flow")
    public BaseResponse<Map<String, Object>> generateCompleteExperimentFlow(
            @RequestParam String prompt,
            @RequestParam(defaultValue = "15") int generateCount,
            @RequestParam(defaultValue = "4") int finalCount,
            @RequestParam(defaultValue = "150") int visitorCount,
            @RequestParam(defaultValue = "7") int daysAgo) {
        Map<String, Object> result = variantGenerationService.generateCompleteExperimentFlow(
                prompt, generateCount, finalCount, visitorCount, daysAgo);
        return BaseResponse.of("完整实验流程演示成功", result);
    }
}
