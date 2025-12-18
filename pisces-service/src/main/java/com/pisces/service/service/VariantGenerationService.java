package com.pisces.service.service;

import java.util.List;
import java.util.Map;

/**
 * 变体生成服务接口
 * AI赋能：使用生成式AI批量产出高质量实验变体，结合智能筛选机制确保变体质量
 */
public interface VariantGenerationService {
    
    /**
     * 生成文本类变体（商品标题、详情页文案、咨询话术等）
     * @param prompt 生成提示词（包含目标人群、核心卖点、风格要求、约束条件等）
     * @param count 生成数量
     * @return 生成的变体列表
     */
    List<String> generateTextVariants(String prompt, int count);
    
    /**
     * 生成图像类变体（商品主图、详情页配图等）
     * @param prompt 生成提示词（包含主体元素、风格要求、场景设定、细节特征等）
     * @param count 生成数量
     * @return 生成的图像URL列表
     */
    List<String> generateImageVariants(String prompt, int count);
    
    /**
     * 智能筛选变体（二级筛选机制）
     * @param variants 待筛选的变体列表
     * @param variantType 变体类型（TEXT/IMAGE）
     * @return 筛选后的高潜力变体列表
     */
    List<String> filterVariants(List<String> variants, VariantType variantType);
    
    /**
     * 评估变体质量（效果预评估）
     * @param variant 变体内容
     * @param variantType 变体类型
     * @return 质量评分（0.0-1.0）和评估详情
     */
    Map<String, Object> evaluateVariant(String variant, VariantType variantType);
    
    /**
     * 变体类型枚举
     */
    enum VariantType {
        TEXT,   // 文本类变体
        IMAGE   // 图像类变体
    }
}
