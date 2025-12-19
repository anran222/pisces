package com.pisces.service.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import com.pisces.service.service.VariantGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 变体生成服务实现
 * 注意：此实现为基础框架，实际生产环境需要集成外部生成式AI服务（如GPT-4、Claude、文心一言等）
 */
@Slf4j
@Service
public class VariantGenerationServiceImpl implements VariantGenerationService {
    
    @Autowired
    private TongYiConfig tongYiConfig;
    
    @Autowired
    private ExperimentService experimentService;
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired
    private DataService dataService;
    
    @Autowired
    private AnalysisService analysisService;
    
    private final Random random = new Random();
    
    @Override
    public List<String> generateTextVariants(String prompt, int count) {
        log.info("生成文本变体: prompt={}, count={}", prompt, count);
        
        // 检查是否启用通义API
        log.debug("通义API配置检查: enabled={}, apiKey配置={}", 
                tongYiConfig.isEnabled(), 
                StringUtils.hasText(tongYiConfig.getApiKey()) ? "已配置" : "未配置");
        
        if (!tongYiConfig.isEnabled() || !StringUtils.hasText(tongYiConfig.getApiKey())) {
            log.warn("通义API未启用或API Key未配置，使用模拟数据");
            return generateMockVariants(prompt, count);
        }
        
        try {
            // 构建结构化Prompt
            String structuredPrompt = buildStructuredPrompt(prompt, count);
            log.debug("构建的结构化Prompt: {}", structuredPrompt);
            
            // 调用通义API生成变体
            log.info("开始调用通义API，模型: {}", tongYiConfig.getModel());
            List<String> variants = callTongYiAPI(structuredPrompt, count);
            
            if (variants.isEmpty()) {
                log.warn("通义API返回空结果，回退到模拟数据");
                return generateMockVariants(prompt, count);
            }
            
            log.info("成功生成 {} 个文本变体", variants.size());
            return variants;
            
        } catch (Exception e) {
            log.error("调用通义API失败，回退到模拟数据。错误信息: {}", e.getMessage(), e);
            return generateMockVariants(prompt, count);
        }
    }
    
    /**
     * 构建结构化Prompt
     * 包含：目标人群、核心卖点、风格要求、约束条件、输出格式
     */
    private String buildStructuredPrompt(String originalPrompt, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的文案生成助手。请根据以下要求生成").append(count).append("个不同的文案变体。\n\n");
        sb.append("原始需求：").append(originalPrompt).append("\n\n");
        sb.append("要求：\n");
        sb.append("1. 每个变体都要有独特的表达方式，避免重复\n");
        sb.append("2. 文案要简洁有力，突出核心卖点\n");
        sb.append("3. 符合商业规范，不包含违规内容\n");
        sb.append("4. 每个变体长度控制在50-200字之间\n\n");
        sb.append("请直接输出").append(count).append("个文案变体，每个变体一行，用换行符分隔。");
        
        return sb.toString();
    }
    
    /**
     * 调用通义千问API
     */
    private List<String> callTongYiAPI(String prompt, int count) throws Exception {
        // 获取API Key
        String apiKey = tongYiConfig.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key未配置");
        }
        
        // 同时设置系统属性（某些SDK版本可能需要）
        System.setProperty("DASHSCOPE_API_KEY", apiKey);
        log.debug("已设置DASHSCOPE_API_KEY，API Key长度: {}", apiKey.length());
        
        Generation gen = new Generation();
        
        // 构建系统消息
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("你是一个专业的文案生成助手，擅长生成多样化、高质量的商业文案。")
                .build();
        
        // 构建用户消息
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();
        
        // 构建请求参数（关键：需要在GenerationParam中显式传递apiKey）
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)  // 显式传递API Key
                .model(tongYiConfig.getModel())
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat("text")
                .build();
        
        log.info("准备调用通义API，模型: {}, 消息数量: {}", tongYiConfig.getModel(), param.getMessages().size());
        
        // 调用API
        GenerationResult result = gen.call(param);
        
        log.debug("通义API调用完成，结果: {}", result != null ? "非空" : "为空");
        
        // 解析返回结果
        if (result == null) {
            log.warn("通义API返回结果为null");
            return new ArrayList<>();
        }
        
        if (result.getOutput() == null) {
            log.warn("通义API返回的Output为null，错误信息: {}", result.getUsage());
            return new ArrayList<>();
        }
        
        String responseText = result.getOutput().getText();
        if (!StringUtils.hasText(responseText)) {
            log.warn("通义API返回的文本为空，Output: {}", result.getOutput());
            return new ArrayList<>();
        }
        
        log.info("通义API返回原始文本长度: {} 字符", responseText.length());
        log.debug("通义API返回原始文本: {}", responseText);
        
        // 解析变体列表（按换行符分割）
        List<String> variants = Arrays.stream(responseText.split("\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(v -> v.length() >= 10) // 过滤太短的文本
                .limit(count)
                .collect(Collectors.toList());
        
        // 如果解析出的变体数量不足，尝试其他分割方式
        if (variants.size() < count) {
            // 尝试按数字编号分割（如：1. xxx 2. xxx）
            String[] parts = responseText.split("\\d+[.、]");
            if (parts.length > 1) {
                variants = Arrays.stream(parts)
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .filter(v -> v.length() >= 10)
                        .limit(count)
                        .collect(Collectors.toList());
            }
        }
        
        // 如果仍然不足，尝试按句号分割
        if (variants.size() < count) {
            String[] sentences = responseText.split("[。！？]");
            variants = Arrays.stream(sentences)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .filter(v -> v.length() >= 10)
                    .limit(count)
                    .collect(Collectors.toList());
        }
        
        return variants;
    }
    
    /**
     * 生成模拟变体（当API不可用时使用）
     */
    private List<String> generateMockVariants(String prompt, int count) {
        List<String> variants = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            variants.add("生成的变体 " + (i + 1) + " (基于Prompt: " + prompt + ")");
        }
        return variants;
    }
    
    @Override
    public List<String> generateImageVariants(String prompt, int count) {
        log.info("生成图像变体: prompt={}, count={}", prompt, count);
        
        // 检查是否启用通义API
        if (!tongYiConfig.isEnabled() || !StringUtils.hasText(tongYiConfig.getApiKey())) {
            log.warn("通义API未启用或API Key未配置，使用模拟数据");
            return generateMockImageVariants(prompt, count);
        }
        
        try {
            // 构建结构化图像生成Prompt
            String structuredPrompt = buildImagePrompt(prompt);
            
            // 调用通义图像生成API
            List<String> imageUrls = callTongYiImageAPI(structuredPrompt, count);
            
            if (imageUrls.isEmpty()) {
                log.warn("通义图像API返回空结果，回退到模拟数据");
                return generateMockImageVariants(prompt, count);
            }
            
            log.info("成功生成 {} 个图像变体", imageUrls.size());
            return imageUrls;
            
        } catch (Exception e) {
            log.error("调用通义图像API失败，回退到模拟数据。错误信息: {}", e.getMessage(), e);
            return generateMockImageVariants(prompt, count);
        }
    }
    
    /**
     * 构建图像生成Prompt
     */
    private String buildImagePrompt(String originalPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("高质量商业产品图片，").append(originalPrompt);
        sb.append("，专业摄影，清晰细节，商业级品质，白色背景");
        return sb.toString();
    }
    
    /**
     * 调用通义图像生成API
     * 注意：当前DashScope SDK 2.11.0可能不包含图像生成API，这里先实现框架
     * 实际使用时需要根据SDK版本调整或使用HTTP直接调用
     */
    private List<String> callTongYiImageAPI(String prompt, int count) throws Exception {
        String apiKey = tongYiConfig.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key未配置");
        }
        
        System.setProperty("DASHSCOPE_API_KEY", apiKey);
        log.debug("已设置DASHSCOPE_API_KEY，准备生成图像");
        
        // TODO: 集成通义万相图像生成API
        // 方式1：如果SDK支持，使用SDK调用
        // 方式2：使用HTTP直接调用通义万相API
        // 当前版本先返回模拟数据，实际使用时需要根据API文档实现
        
        log.warn("图像生成API暂未实现，返回模拟数据。请根据DashScope API文档实现图像生成功能");
        return generateMockImageVariants(prompt, count);
        
        // 以下是使用HTTP直接调用的示例代码框架（需要根据实际API文档调整）：
        /*
        List<String> imageUrls = new ArrayList<>();
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation";
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "wanx-v1");
        requestBody.put("input", Map.of("prompt", prompt));
        requestBody.put("parameters", Map.of(
            "n", Math.min(4, count),
            "size", "1024*1024"
        ));
        
        // 使用RestTemplate或HttpClient调用API
        // ... 实现HTTP调用逻辑 ...
        
        return imageUrls;
        */
    }
    
    /**
     * 生成模拟图像变体
     */
    private List<String> generateMockImageVariants(String prompt, int count) {
        List<String> imageUrls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            imageUrls.add("https://example.com/generated-image-" + (i + 1) + ".jpg");
        }
        return imageUrls;
    }
    
    @Override
    public List<String> filterVariants(List<String> variants, VariantType variantType) {
        log.info("筛选变体: variantType={}, count={}", variantType, variants.size());
        
        // 一级筛选：规则过滤（合规规则、业务规则、技术规则）
        List<String> filteredByRules = filterByRules(variants, variantType);
        log.debug("规则过滤后剩余: {}", filteredByRules.size());
        
        // 二级筛选：算法预评估（去重处理、效果预评估）
        List<String> filteredByAlgorithm = filterByAlgorithm(filteredByRules, variantType);
        log.debug("算法筛选后剩余: {}", filteredByAlgorithm.size());
        
        return filteredByAlgorithm;
    }
    
    @Override
    public Map<String, Object> evaluateVariant(String variant, VariantType variantType) {
        log.debug("评估变体质量: variantType={}, variant={}", variantType, variant);
        
        Map<String, Object> result = new HashMap<>();
        
        if (variantType == VariantType.TEXT) {
            // 文本变体评估：基于多个维度
            double qualityScore = evaluateTextVariant(variant);
            result.put("qualityScore", qualityScore);
            result.put("predictedLift", qualityScore * 0.15); // 预测提升幅度（0-15%）
            result.put("confidence", 0.75 + (qualityScore - 0.5) * 0.5); // 置信度随质量分数提升
            result.put("evaluationDetails", getTextEvaluationDetails(variant, qualityScore));
        } else {
            // 图像变体评估（简化）
            double qualityScore = 0.7 + Math.random() * 0.2; // 0.7-0.9
            result.put("qualityScore", qualityScore);
            result.put("predictedLift", qualityScore * 0.1);
            result.put("confidence", 0.8);
        }
        
        return result;
    }
    
    /**
     * 评估文本变体质量（基于多个维度）
     */
    private double evaluateTextVariant(String variant) {
        double score = 0.0;
        int factors = 0;
        
        // 1. 长度评估（50-200字为最佳）
        int length = variant.length();
        double lengthScore = 1.0;
        if (length < 20) {
            lengthScore = 0.5; // 太短
        } else if (length > 300) {
            lengthScore = 0.7; // 太长
        } else if (length >= 50 && length <= 200) {
            lengthScore = 1.0; // 最佳长度
        } else {
            lengthScore = 0.8; // 可接受
        }
        score += lengthScore * 0.2;
        factors++;
        
        // 2. 关键词密度（包含吸引人的词汇）
        String[] attractiveKeywords = {"优惠", "特价", "限时", "品质", "保障", "放心", "专业", 
                                       "精选", "超值", "惊喜", "立即", "现在", "轻松", "便捷"};
        int keywordCount = 0;
        for (String keyword : attractiveKeywords) {
            if (variant.contains(keyword)) {
                keywordCount++;
            }
        }
        double keywordScore = Math.min(1.0, keywordCount / 3.0); // 最多3个关键词得满分
        score += keywordScore * 0.3;
        factors++;
        
        // 3. 情感倾向（积极词汇）
        String[] positiveWords = {"好", "优", "强", "高", "新", "快", "省", "值", "赞", "棒"};
        int positiveCount = 0;
        for (String word : positiveWords) {
            if (variant.contains(word)) {
                positiveCount++;
            }
        }
        double positiveScore = Math.min(1.0, positiveCount / 5.0);
        score += positiveScore * 0.2;
        factors++;
        
        // 4. 结构完整性（包含标点、分段等）
        boolean hasPunctuation = variant.matches(".*[。！？，、].*");
        boolean hasStructure = variant.length() > 30 && (variant.contains("，") || variant.contains("。"));
        double structureScore = (hasPunctuation ? 0.5 : 0.0) + (hasStructure ? 0.5 : 0.0);
        score += structureScore * 0.15;
        factors++;
        
        // 5. 独特性（避免重复字符过多）
        long uniqueChars = variant.chars().distinct().count();
        double uniquenessScore = Math.min(1.0, uniqueChars / 30.0);
        score += uniquenessScore * 0.15;
        factors++;
        
        // 归一化到0.5-1.0范围
        double normalizedScore = 0.5 + (score / factors) * 0.5;
        return Math.min(1.0, Math.max(0.5, normalizedScore));
    }
    
    /**
     * 获取文本评估详情
     */
    private Map<String, Object> getTextEvaluationDetails(String variant, double qualityScore) {
        Map<String, Object> details = new HashMap<>();
        details.put("length", variant.length());
        details.put("lengthStatus", variant.length() >= 50 && variant.length() <= 200 ? "optimal" : "acceptable");
        details.put("qualityLevel", qualityScore >= 0.8 ? "high" : qualityScore >= 0.65 ? "medium" : "low");
        details.put("recommendation", qualityScore >= 0.8 ? "强烈推荐" : qualityScore >= 0.65 ? "推荐使用" : "建议优化");
        return details;
    }
    
    /**
     * 一级筛选：规则过滤
     */
    private List<String> filterByRules(List<String> variants, VariantType variantType) {
        return variants.stream()
                .filter(variant -> {
                    // 合规规则：过滤违规内容
                    if (containsViolation(variant)) {
                        return false;
                    }
                    
                    // 业务规则：检查核心卖点、风格匹配等
                    if (!matchesBusinessRules(variant, variantType)) {
                        return false;
                    }
                    
                    // 技术规则：检查技术可行性
                    if (!isTechnicallyFeasible(variant, variantType)) {
                        return false;
                    }
                    
                    return true;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 二级筛选：算法预评估
     */
    private List<String> filterByAlgorithm(List<String> variants, VariantType variantType) {
        // 去重处理：基于语义相似度算法
        List<String> deduplicated = deduplicateBySimilarity(variants, variantType);
        
        // 效果预评估：筛选Top N高潜力变体
        return deduplicated.stream()
                .map(variant -> {
                    Map<String, Object> evaluation = evaluateVariant(variant, variantType);
                    double score = (Double) evaluation.get("qualityScore");
                    return new VariantWithScore(variant, score);
                })
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(Math.min(5, deduplicated.size())) // 保留Top 5
                .map(v -> v.variant)
                .collect(Collectors.toList());
    }
    
    /**
     * 去重处理：基于语义相似度
     */
    private List<String> deduplicateBySimilarity(List<String> variants, VariantType variantType) {
        // TODO: 实现语义相似度算法
        // 文本类变体：使用BERT等模型计算语义相似度
        // 图像类变体：使用CNN特征提取计算相似度
        // 相似度>60%的变体只保留一个
        
        // 简化实现：基于字符串相似度
        List<String> result = new ArrayList<>();
        for (String variant : variants) {
            boolean isDuplicate = false;
            for (String existing : result) {
                double similarity = calculateSimilarity(variant, existing);
                if (similarity > 0.6) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                result.add(variant);
            }
        }
        return result;
    }
    
    /**
     * 计算相似度（简化实现）
     */
    private double calculateSimilarity(String a, String b) {
        // TODO: 使用专业的语义相似度算法
        // 这里使用简单的字符串匹配作为示例
        int commonChars = 0;
        int minLength = Math.min(a.length(), b.length());
        for (int i = 0; i < minLength; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                commonChars++;
            }
        }
        return minLength > 0 ? (double) commonChars / minLength : 0.0;
    }
    
    private boolean containsViolation(String variant) {
        // TODO: 实现违规内容检测
        // 检查是否包含虚假宣传、违规词汇等
        return false;
    }
    
    private boolean matchesBusinessRules(String variant, VariantType variantType) {
        // TODO: 实现业务规则检查
        // 检查核心卖点、风格匹配等
        return true;
    }
    
    private boolean isTechnicallyFeasible(String variant, VariantType variantType) {
        // TODO: 实现技术可行性检查
        return true;
    }
    
    @Override
    public Map<String, Object> generateCompleteTextExperiment(String prompt, int generateCount, int finalCount) {
        log.info("开始生成完整文本实验体: prompt={}, generateCount={}, finalCount={}", prompt, generateCount, finalCount);
        
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        
        try {
            // 步骤1：生成文本变体
            log.info("步骤1: 生成文本变体，目标数量: {}", generateCount);
            List<String> generatedVariants = generateTextVariants(prompt, generateCount);
            result.put("generatedCount", generatedVariants.size());
            result.put("generatedVariants", generatedVariants);
            log.info("步骤1完成: 成功生成 {} 个变体", generatedVariants.size());
            
            if (generatedVariants.isEmpty()) {
                result.put("error", "未能生成任何变体");
                return result;
            }
            
            // 步骤2：智能筛选变体
            log.info("步骤2: 智能筛选变体，从 {} 个中筛选出 {}", generatedVariants.size(), finalCount);
            List<String> filteredVariants = filterVariants(generatedVariants, VariantType.TEXT);
            result.put("filteredCount", filteredVariants.size());
            result.put("filteredVariants", filteredVariants);
            log.info("步骤2完成: 筛选后剩余 {} 个变体", filteredVariants.size());
            
            // 步骤3：评估变体质量
            log.info("步骤3: 评估变体质量");
            List<Map<String, Object>> evaluations = new ArrayList<>();
            List<Map<String, Object>> finalVariants = new ArrayList<>();
            
            for (String variant : filteredVariants) {
                Map<String, Object> evaluation = evaluateVariant(variant, VariantType.TEXT);
                evaluation.put("variant", variant);
                evaluations.add(evaluation);
                
                // 构建最终变体信息
                Map<String, Object> finalVariant = new HashMap<>();
                finalVariant.put("variant", variant);
                finalVariant.put("qualityScore", evaluation.get("qualityScore"));
                finalVariant.put("predictedLift", evaluation.get("predictedLift"));
                finalVariant.put("confidence", evaluation.get("confidence"));
                finalVariant.put("evaluationDetails", evaluation.get("evaluationDetails"));
                finalVariants.add(finalVariant);
            }
            
            // 按质量分数排序，取Top N
            finalVariants.sort((a, b) -> {
                Double scoreA = (Double) a.get("qualityScore");
                Double scoreB = (Double) b.get("qualityScore");
                return Double.compare(scoreB, scoreA);
            });
            
            List<Map<String, Object>> topVariants = finalVariants.stream()
                    .limit(finalCount)
                    .collect(Collectors.toList());
            
            result.put("evaluations", evaluations);
            result.put("finalVariants", topVariants);
            result.put("finalCount", topVariants.size());
            log.info("步骤3完成: 最终选出 {} 个高质量变体", topVariants.size());
            
            // 统计信息
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalGenerated", generatedVariants.size());
            statistics.put("afterFiltering", filteredVariants.size());
            statistics.put("finalSelected", topVariants.size());
            statistics.put("filterRate", String.format("%.2f%%", 
                    (double) filteredVariants.size() / generatedVariants.size() * 100));
            statistics.put("selectionRate", String.format("%.2f%%", 
                    (double) topVariants.size() / generatedVariants.size() * 100));
            
            if (!topVariants.isEmpty()) {
                double avgQualityScore = topVariants.stream()
                        .mapToDouble(v -> (Double) v.get("qualityScore"))
                        .average()
                        .orElse(0.0);
                statistics.put("averageQualityScore", String.format("%.2f", avgQualityScore));
                
                double avgPredictedLift = topVariants.stream()
                        .mapToDouble(v -> (Double) v.get("predictedLift"))
                        .average()
                        .orElse(0.0);
                statistics.put("averagePredictedLift", String.format("%.2f%%", avgPredictedLift * 100));
            }
            
            result.put("statistics", statistics);
            result.put("success", true);
            result.put("message", "完整文本实验体生成成功");
            
            long endTime = System.currentTimeMillis();
            result.put("duration", endTime - startTime);
            result.put("durationFormatted", String.format("%.2f秒", (endTime - startTime) / 1000.0));
            
            log.info("完整文本实验体生成完成，耗时: {}ms", endTime - startTime);
            
        } catch (Exception e) {
            log.error("生成完整文本实验体失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public Map<String, Object> generateCompleteExperimentFlow(String prompt, int generateCount, int finalCount,
                                                               int visitorCount, int daysAgo) {
        log.info("开始完整实验流程演示: prompt={}, generateCount={}, finalCount={}, visitorCount={}, daysAgo={}",
                prompt, generateCount, finalCount, visitorCount, daysAgo);
        
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        
        try {
            // 步骤1：生成文本变体
            log.info("=".repeat(60));
            log.info("步骤1: 生成文本变体");
            log.info("=".repeat(60));
            Map<String, Object> variantResult = generateCompleteTextExperiment(prompt, generateCount, finalCount);
            
            if (!Boolean.TRUE.equals(variantResult.get("success"))) {
                result.put("success", false);
                result.put("error", "变体生成失败: " + variantResult.get("error"));
                return result;
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> finalVariants = (List<Map<String, Object>>) variantResult.get("finalVariants");
            if (finalVariants == null || finalVariants.isEmpty()) {
                result.put("success", false);
                result.put("error", "未生成任何有效变体");
                return result;
            }
            
            // 不返回变体生成的中间过程数据，只保留最终筛选出的变体数量
            log.info("步骤1完成: 成功生成 {} 个高质量变体", finalVariants.size());
            
            // 步骤2：创建实验
            log.info("=".repeat(60));
            log.info("步骤2: 创建实验");
            log.info("=".repeat(60));
            String experimentId = createExperimentFromVariants(prompt, finalVariants, daysAgo);
            result.put("experimentId", experimentId);
            log.info("步骤2完成: 实验创建成功，实验ID={}", experimentId);
            
            // 步骤3：启动实验
            log.info("=".repeat(60));
            log.info("步骤3: 启动实验");
            log.info("=".repeat(60));
            experimentService.startExperiment(experimentId);
            result.put("experimentStatus", "RUNNING");
            log.info("步骤3完成: 实验已启动");
            
            // 步骤4：生成实验数据
            log.info("=".repeat(60));
            log.info("步骤4: 生成实验数据");
            log.info("=".repeat(60));
            generateExperimentData(experimentId, finalVariants, visitorCount);
            result.put("dataGenerated", true);
            result.put("visitorCount", visitorCount * finalVariants.size());
            log.info("步骤4完成: 已生成 {} 个访客的实验数据", visitorCount * finalVariants.size());
            
            // 步骤5：分析实验
            log.info("=".repeat(60));
            log.info("步骤5: 分析实验");
            log.info("=".repeat(60));
            Map<String, Object> analysisResult = performAnalysis(experimentId, finalVariants);
            
            // 精简返回结果：只返回最终变体的核心实验信息
            result.put("experimentId", experimentId);
            result.put("variants", analysisResult.get("variants")); // 只返回变体结果
            
            // 获取最佳变体
            Object summaryObj = analysisResult.get("summary");
            if (summaryObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> summary = (Map<String, Object>) summaryObj;
                result.put("bestVariant", summary.get("bestVariant"));
            }
            
            result.put("success", true);
            
            long endTime = System.currentTimeMillis();
            result.put("duration", endTime - startTime);
            result.put("durationFormatted", String.format("%.2f秒", (endTime - startTime) / 1000.0));
            
            log.info("步骤5完成: 实验分析完成");
            
            log.info("=".repeat(60));
            log.info("完整实验流程演示完成，总耗时: {}ms", endTime - startTime);
            log.info("=".repeat(60));
            
        } catch (Exception e) {
            log.error("完整实验流程演示失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 基于变体创建实验
     */
    private String createExperimentFromVariants(String prompt, List<Map<String, Object>> variants, int daysAgo) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusDays(daysAgo);
        LocalDateTime endTime = now.plusDays(14);
        
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName("AI生成变体实验 - " + prompt);
        request.setDescription("基于AI生成的变体创建的实验，包含" + variants.size() + "个实验组");
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        
        // 创建实验组（第一个作为基准组，其他作为变体组）
        List<ExperimentCreateRequest.GroupConfig> groups = new ArrayList<>();
        double trafficPerGroup = 1.0 / variants.size();
        
        for (int i = 0; i < variants.size(); i++) {
            Map<String, Object> variant = variants.get(i);
            ExperimentCreateRequest.GroupConfig group = new ExperimentCreateRequest.GroupConfig();
            group.setId("group_" + (i == 0 ? "A" : String.valueOf((char)('A' + i))));
            group.setName(i == 0 ? "基准组" : "变体组" + i);
            group.setTrafficRatio(trafficPerGroup);
            
            // 将变体文案作为配置
            Map<String, Object> config = new HashMap<>();
            config.put("variant", variant.get("variant"));
            config.put("qualityScore", variant.get("qualityScore"));
            config.put("predictedLift", variant.get("predictedLift"));
            group.setConfig(config);
            
            groups.add(group);
        }
        request.setGroups(groups);
        
        // 配置流量分配（使用Thompson Sampling）
        ExperimentCreateRequest.TrafficConfigRequest trafficConfig = new ExperimentCreateRequest.TrafficConfigRequest();
        trafficConfig.setTotalTraffic(1.0);
        trafficConfig.setStrategy("THOMPSON_SAMPLING");
        
        List<ExperimentCreateRequest.GroupAllocationRequest> allocations = new ArrayList<>();
        for (ExperimentCreateRequest.GroupConfig group : groups) {
            ExperimentCreateRequest.GroupAllocationRequest allocation = 
                    new ExperimentCreateRequest.GroupAllocationRequest();
            allocation.setGroup(group.getId());
            allocation.setRatio(group.getTrafficRatio());
            allocations.add(allocation);
        }
        trafficConfig.setAllocation(allocations);
        request.setTraffic(trafficConfig);
        
        Experiment experiment = experimentService.createExperiment(request);
        return experiment.getId();
    }
    
    /**
     * 生成实验数据
     */
    private void generateExperimentData(String experimentId, List<Map<String, Object>> variants, int visitorCount) {
        Map<String, List<String>> groupVisitors = new HashMap<>();
        
        // 为每个实验组生成访客并分配
        for (int i = 0; i < variants.size(); i++) {
            String groupId = "group_" + (i == 0 ? "A" : String.valueOf((char)('A' + i)));
            List<String> visitors = new ArrayList<>();
            
            for (int j = 0; j < visitorCount; j++) {
                String visitorId = "visitor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                // 分配访客到实验组
                String assignedGroup = trafficService.assignGroup(experimentId, visitorId);
                if (groupId.equals(assignedGroup)) {
                    visitors.add(visitorId);
                }
            }
            
            // 如果分配的访客不足，补充一些
            while (visitors.size() < visitorCount) {
                String visitorId = "visitor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                visitors.add(visitorId);
            }
            
            groupVisitors.put(groupId, visitors);
        }
        
        // 生成事件数据
        for (Map.Entry<String, List<String>> entry : groupVisitors.entrySet()) {
            String groupId = entry.getKey();
            List<String> visitors = entry.getValue();
            
            // 根据组索引设置不同的转化率（模拟变体效果差异）
            int groupIndex = groupId.charAt(groupId.length() - 1) - 'A';
            double baseConversionRate = 0.10; // 基准组10%
            double conversionRate = baseConversionRate + (groupIndex * 0.02); // 每个变体组提升2%
            
            for (String visitorId : visitors) {
                // 所有访客都有浏览事件
                dataService.reportEvent(experimentId, visitorId, "VIEW", "商品详情页浏览", null);
                
                // 80%的访客有点击事件
                if (random.nextDouble() < 0.8) {
                    dataService.reportEvent(experimentId, visitorId, "CLICK", "价格咨询点击", null);
                }
                
                // 根据转化率生成转化事件
                if (random.nextDouble() < conversionRate) {
                    dataService.reportEvent(experimentId, visitorId, "CONVERT", "成交", null);
                }
            }
        }
    }
    
    /**
     * 执行实验分析（按变体组织结果）
     */
    private Map<String, Object> performAnalysis(String experimentId, List<Map<String, Object>> variants) {
        Map<String, Object> analysisResult = new HashMap<>();
        
        log.info("开始分析实验，实验ID: {}, 变体数量: {}", experimentId, variants.size());
        
        // 获取统计数据
        com.pisces.common.model.Statistics statistics = analysisService.getStatistics(experimentId);
        if (statistics == null || statistics.getGroupStatistics() == null) {
            log.warn("无法获取实验统计数据: {}", experimentId);
            return analysisResult;
        }
        
        log.debug("获取到统计数据，组数量: {}", statistics.getGroupStatistics().size());
        
        // 获取贝叶斯分析
        Map<String, Object> bayesianAnalysis = analysisService.getBayesianAnalysis(experimentId);
        Map<String, Double> winRates = new HashMap<>();
        String baselineGroup = null;
        if (bayesianAnalysis != null) {
            @SuppressWarnings("unchecked")
            Map<String, Double> rates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            if (rates != null) {
                winRates.putAll(rates);
            }
            baselineGroup = (String) bayesianAnalysis.get("baselineGroup");
        }
        
        // 获取基准组统计数据
        Map<String, com.pisces.common.model.Statistics.GroupStatistics> groupStats = 
                statistics.getGroupStatistics();
        com.pisces.common.model.Statistics.GroupStatistics baselineStats = null;
        if (baselineGroup != null && groupStats != null) {
            baselineStats = groupStats.get(baselineGroup);
        }
        
        // 为每个变体构建独立的实验结果
        List<Map<String, Object>> variantResults = new ArrayList<>();
        
        if (groupStats == null) {
            log.warn("实验组统计数据为空，无法构建变体结果");
            return analysisResult;
        }
        
        for (int i = 0; i < variants.size(); i++) {
            Map<String, Object> variant = variants.get(i);
            String groupId = "group_" + (i == 0 ? "A" : String.valueOf((char)('A' + i)));
            
            Map<String, Object> variantResult = new HashMap<>();
            
            // 只保留核心信息：变体文案、转化率、相对变化、胜率
            variantResult.put("variant", variant.get("variant")); // 变体文案
            variantResult.put("variantIndex", i + 1);
            variantResult.put("isBaseline", groupId.equals(baselineGroup));
            
            // 获取该变体的实验数据
            com.pisces.common.model.Statistics.GroupStatistics groupStatistics = groupStats.get(groupId);
            if (groupStatistics != null) {
                // 核心指标：访客数和转化率
                variantResult.put("visitorCount", groupStatistics.getUserCount());
                Double conversionRate = groupStatistics.getConversionRate();
                variantResult.put("conversionRate", conversionRate);
                
                // 如果是变体组，计算相对基准的变化
                if (!groupId.equals(baselineGroup) && baselineStats != null) {
                    Double baselineRate = baselineStats.getConversionRate();
                    if (baselineRate != null && conversionRate != null) {
                        double rateChangePercent = baselineRate > 0 ? 
                                ((conversionRate - baselineRate) / baselineRate) * 100 : 0;
                        variantResult.put("conversionRateChangePercent", rateChangePercent);
                        variantResult.put("isBetter", conversionRate > baselineRate);
                    }
                }
            }
            
            // 贝叶斯胜率（只保留核心信息）
            Double winRate = winRates.get(groupId);
            if (winRate != null) {
                variantResult.put("winRate", winRate * 100); // 直接返回百分比
                variantResult.put("canEarlyStop", winRate >= 0.95 || winRate <= 0.05);
            }
            
            variantResults.add(variantResult);
        }
        
        // 组织返回结果：只返回精简的变体结果
        analysisResult.put("variants", variantResults);
        
        log.info("已构建 {} 个变体的实验结果", variantResults.size());
        
        // 找出最佳变体（只保留核心信息）
        Map<String, Object> summary = new HashMap<>();
        Map<String, Object> bestVariant = null;
        double bestWinRate = 0.0;
        for (Map<String, Object> vr : variantResults) {
            if (!Boolean.TRUE.equals(vr.get("isBaseline"))) {
                Double wr = (Double) vr.get("winRate");
                if (wr != null && wr > bestWinRate) {
                    bestWinRate = wr;
                    bestVariant = vr;
                }
            }
        }
        if (bestVariant != null) {
            Map<String, Object> bestVariantInfo = new HashMap<>();
            bestVariantInfo.put("variantIndex", bestVariant.get("variantIndex"));
            bestVariantInfo.put("variant", bestVariant.get("variant"));
            bestVariantInfo.put("winRate", bestWinRate);
            bestVariantInfo.put("conversionRate", bestVariant.get("conversionRate"));
            summary.put("bestVariant", bestVariantInfo);
            
            log.info("最佳变体: 变体{}, 胜率: {:.2f}%", 
                    bestVariant.get("variantIndex"), String.format("%.2f", bestWinRate));
        }
        
        analysisResult.put("summary", summary);
        
        log.info("实验分析完成，返回 {} 个变体的精简结果", variantResults.size());
        return analysisResult;
    }
    
    /**
     * 变体与评分
     */
    private static class VariantWithScore {
        String variant;
        double score;
        
        VariantWithScore(String variant, double score) {
            this.variant = variant;
            this.score = score;
        }
    }
}
