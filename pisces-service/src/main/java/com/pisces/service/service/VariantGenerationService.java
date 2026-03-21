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
     * 生成图像类变体（支持纯文生图和带参考图转换）
     * @param prompt 生成提示词
     * @param count 生成数量
     * @param sourceContext 上下文信息，可包含参考图URL或Base64
     * @return 生成的图像URL列表
     */
    List<String> generateImageVariants(String prompt, int count, Map<String, Object> sourceContext);
}
