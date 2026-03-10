package com.pisces.service.service.impl;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.OSSUtils;
import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import com.pisces.service.service.VariantGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        ensureTongYiAvailable();
        
        try {
            String structuredPrompt = buildStructuredPrompt(prompt, count);
            log.info("开始调用通义API，模型: {}", tongYiConfig.getModel());
            List<String> variants = callTongYiAPI(structuredPrompt, count);
            
            if (variants.isEmpty()) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义文本生成未返回有效变体");
            }
            
            log.info("成功生成 {} 个文本变体", variants.size());
            return variants;
            
        } catch (Exception e) {
            log.error("调用通义API失败。错误信息: {}", e.getMessage(), e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义文本生成失败: " + e.getMessage());
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
    
    @Override
    public List<String> generateImageVariants(String prompt, int count) {
        log.info("生成图像变体: prompt={}, count={}", prompt, count);
        ensureTongYiAvailable();
        
        try {
            String structuredPrompt = buildImagePrompt(prompt);
            List<String> imageUrls = callTongYiImageAPI(structuredPrompt, count);
            
            if (imageUrls.isEmpty()) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图像生成未返回有效图片");
            }
            
            log.info("成功生成 {} 个图像变体", imageUrls.size());
            // 打印每个URL帮助调试
            for (int i = 0; i < imageUrls.size(); i++) {
                log.info("图像变体 {}: {}", i + 1, imageUrls.get(i));
            }
            return imageUrls;
            
        } catch (Exception e) {
            log.error("调用通义图像API失败。错误信息: {}", e.getMessage(), e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图像生成失败: " + e.getMessage());
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
     * 调用通义万相图像生成API
     * 使用HTTP直接调用DashScope API
     */
    private List<String> callTongYiImageAPI(String prompt, int count) throws Exception {
        String apiKey = tongYiConfig.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key未配置");
        }
        
        List<String> imageUrls = new ArrayList<>();
        
        try {
            // 使用通义万相的文生图模型
            String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "wanx-v1");
            
            Map<String, Object> input = new HashMap<>();
            input.put("prompt", prompt);
            requestBody.put("input", input);
            
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("n", Math.min(4, count)); // 单次最多生成4张
            parameters.put("size", "1024*1024");
            parameters.put("style", "<auto>");
            requestBody.put("parameters", parameters);
            
            // 创建HTTP请求
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .build();
            
            String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(requestBody);
            
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();
            
            log.info("发送图像生成请求到通义万相API");
            
            java.net.http.HttpResponse<String> response = client.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                // 解析异步任务响应
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(response.body(), Map.class);
                
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
                if (output != null) {
                    String taskId = (String) output.get("task_id");
                    String taskStatus = (String) output.get("task_status");
                    
                    log.info("图像生成任务已提交，任务ID: {}, 状态: {}", taskId, taskStatus);
                    
                    // 如果是异步任务，等待完成
                    if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
                        imageUrls = waitForImageGenerationResult(apiKey, taskId, 120);
                    } else if ("SUCCEEDED".equals(taskStatus)) {
                        imageUrls = extractImageUrls(output);
                    }
                }
            } else {
                log.error("图像生成API返回错误: status={}, body={}", response.statusCode(), response.body());
            }
            
        } catch (Exception e) {
            log.error("调用通义万相API失败", e);
            throw e;
        }
        
        if (imageUrls.isEmpty()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图像生成未返回图片结果");
        }
        
        return imageUrls;
    }
    
    /**
     * 等待图像生成任务完成
     */
    private List<String> waitForImageGenerationResult(String apiKey, String taskId, int maxWaitSeconds) {
        String statusUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;
        
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        
        int waitedSeconds = 0;
        int pollInterval = 3;
        
        while (waitedSeconds < maxWaitSeconds) {
            try {
                Thread.sleep(pollInterval * 1000L);
                waitedSeconds += pollInterval;
                
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(statusUrl))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .build();
                
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> responseMap = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(response.body(), Map.class);
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
                    if (output != null) {
                        String taskStatus = (String) output.get("task_status");
                        log.debug("任务状态: {}, 已等待: {}秒", taskStatus, waitedSeconds);
                        
                        if ("SUCCEEDED".equals(taskStatus)) {
                            return extractImageUrls(output);
                        } else if ("FAILED".equals(taskStatus)) {
                            log.error("图像生成任务失败: {}", output.get("message"));
                            break;
                        }
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("查询任务状态失败", e);
                break;
            }
        }
        
        return new ArrayList<>();
    }
    
    /**
     * 从API响应中提取图像URL
     */
    private List<String> extractImageUrls(Map<String, Object> output) {
        List<String> urls = new ArrayList<>();
        
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
            if (results != null) {
                for (Map<String, Object> result : results) {
                    String url = (String) result.get("url");
                    if (url != null && !url.isEmpty()) {
                        urls.add(url);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析图像URL失败", e);
        }
        
        return urls;
    }
    
    @Override
    public List<String> generateImageVariantsFromImage(String imageBase64, String prompt, int count) {
        log.info("基于上传图片生成变体: prompt={}, count={}", prompt, count);
        ensureTongYiAvailable();
        
        try {
            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(
                            Collections.singletonMap("image", resolveQwenImageEditInput(imageBase64)),
                            Collections.singletonMap("text", normalizeImageVariantPrompt(prompt))
                    ))
                    .build();

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("watermark", false);
            parameters.put("negative_prompt", buildImageVariantNegativePrompt(prompt));
            parameters.put("n", Math.min(4, count));
            parameters.put("prompt_extend", true);

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(tongYiConfig.getApiKey())
                    .model("qwen-image-2.0-pro")
                    .messages(Collections.singletonList(userMessage))
                    .parameters(parameters)
                    .build();

            MultiModalConversation conversation = new MultiModalConversation();
            MultiModalConversationResult result = conversation.call(param);
            List<String> imageUrls = extractMultiModalImageUrls(result);
            if (imageUrls.isEmpty()) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图生图未返回图片结果");
            }
            return imageUrls;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图生图API调用失败", e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图生图失败: " + e.getMessage());
        }
    }
    
    @Override
    public String editImage(String imageBase64, String maskBase64, String prompt) {
        log.info("图片局部编辑: prompt={}", prompt);
        ensureTongYiAvailable();
        
        try {
            String apiKey = tongYiConfig.getApiKey();
            Map<String, Object> parameters = new HashMap<>();
            if (!StringUtils.hasText(maskBase64)) {
                parameters.put("strength", 0.5);
            }

            ImageSynthesisParam.ImageSynthesisParamBuilder<?, ?> builder = ImageSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model("wanx2.1-imageedit")
                    .prompt(prompt)
                    .baseImageUrl(resolveImageEditInput(imageBase64))
                    .n(1)
                    .size("1024*1024")
                    .promptExtend(true)
                    .parameters(parameters);

            if (StringUtils.hasText(maskBase64)) {
                builder.function(ImageSynthesis.ImageEditFunction.DESCRIPTION_EDIT_WITH_MASK);
                builder.maskImageUrl(resolveImageEditInput(maskBase64));
            } else {
                builder.function(ImageSynthesis.ImageEditFunction.DESCRIPTION_EDIT);
            }

            ImageSynthesis imageSynthesis = new ImageSynthesis();
            ImageSynthesisResult result = imageSynthesis.call(builder.build());
            assertImageSynthesisSucceeded(result, "通义图片编辑");
            List<String> urls = extractImageUrls(result);
            if (!urls.isEmpty()) {
                return urls.get(0);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片编辑API调用失败", e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图片编辑失败: " + e.getMessage());
        }
        
        throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图片编辑未返回结果");
    }
    
    @Override
    public String transferImageStyle(String imageBase64, String style) {
        log.info("图片风格转换: style={}", style);
        ensureTongYiAvailable();

        try {
            String prompt = resolveSupportedStylePrompt(style);
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                    .apiKey(tongYiConfig.getApiKey())
                    .model("wanx2.1-imageedit")
                    .function(ImageSynthesis.ImageEditFunction.STYLIZATION_ALL)
                    .baseImageUrl(resolveImageEditInput(imageBase64))
                    .prompt(prompt)
                    .n(1)
                    .size("1024*1024")
                    .build();

            ImageSynthesis imageSynthesis = new ImageSynthesis();
            ImageSynthesisResult result = imageSynthesis.call(param);
            assertImageSynthesisSucceeded(result, "通义风格转换");

            List<String> urls = extractImageUrls(result);
            if (!urls.isEmpty()) {
                return urls.get(0);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("风格转换API调用失败", e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义风格转换失败: " + e.getMessage());
        }

        throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义风格转换未返回结果");
    }

    private void ensureTongYiAvailable() {
        if (!tongYiConfig.isEnabled()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI未启用，无法执行真实AI流程");
        }
        if (!StringUtils.hasText(tongYiConfig.getApiKey())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置 TONGYI_API_KEY，无法执行真实AI流程");
        }
    }
    
    /**
     * 根据风格名称获取对应的提示词
     */
    private String getStylePrompt(String style) {
        switch (style.toLowerCase()) {
            case "cartoon":
                return "将图片转换为卡通风格，色彩鲜艳，线条简洁，卡通漫画效果";
            case "oil-painting":
                return "将图片转换为油画风格，厚重的笔触，丰富的色彩层次，印象派风格";
            case "sketch":
                return "将图片转换为素描风格，黑白铅笔画效果，精细的线条";
            case "anime":
                return "将图片转换为日本动漫风格，大眼睛，精致的线条，动漫效果";
            case "watercolor":
                return "将图片转换为水彩画风格，柔和的色彩过渡，水彩渲染效果";
            case "pixel":
                return "将图片转换为像素艺术风格，8bit复古游戏风格";
            case "3d":
                return "将图片转换为3D渲染风格，立体感强，光影效果明显";
            case "minimalist":
                return "将图片转换为极简风格，简洁的线条，单一色调";
            default:
                return "优化图片，提升画质，" + style + "风格";
        }
    }

    private String resolveSupportedStylePrompt(String style) {
        switch (style.toLowerCase()) {
            case "french-book":
                return "转换成法国绘本风格";
            case "gold-foil":
                return "转换成金箔艺术风格";
            default:
                throw new IllegalArgumentException("当前仅支持 french-book、gold-foil 两种官方风格");
        }
    }

    private ImageSynthesisResult waitForImageSynthesisResult(ImageSynthesis imageSynthesis,
                                                             ImageSynthesisResult task,
                                                             String apiKey)
            throws ApiException, NoApiKeyException {
        return imageSynthesis.wait(task, apiKey);
    }

    private void assertImageSynthesisSucceeded(ImageSynthesisResult result, String operationName) {
        if (result == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, operationName + "失败: 通义返回结果为空");
        }
        ImageSynthesisOutput output = result.getOutput();
        if (output == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, operationName + "失败: 通义返回缺少 output");
        }

        String taskStatus = normalizeStatus(output.getTaskStatus());
        String code = firstNonBlank(output.getCode(), result.getCode());
        String message = firstNonBlank(output.getMessage(), result.getMessage());
        boolean hasResults = output.getResults() != null && !output.getResults().isEmpty();

        log.info("{}结果: taskId={}, taskStatus={}, code={}, message={}, resultCount={}",
                operationName,
                output.getTaskId(),
                taskStatus,
                code,
                message,
                output.getResults() == null ? 0 : output.getResults().size());

        if (StringUtils.hasText(taskStatus) && !"SUCCEEDED".equals(taskStatus)) {
            throw new BusinessException(
                    ResponseCode.SERVICE_UNAVAILABLE,
                    operationName + "失败: " + buildImageSynthesisFailureMessage(taskStatus, code, message)
            );
        }

        if (!hasResults && (StringUtils.hasText(code) || StringUtils.hasText(message))) {
            throw new BusinessException(
                    ResponseCode.SERVICE_UNAVAILABLE,
                    operationName + "失败: " + buildImageSynthesisFailureMessage(taskStatus, code, message)
            );
        }
    }

    private List<String> extractImageUrls(ImageSynthesisResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getResults() == null) {
            return new ArrayList<>();
        }
        List<String> urls = new ArrayList<>();
        for (Map<String, String> item : result.getOutput().getResults()) {
            String url = firstNonBlank(item.get("url"), item.get("image_url"), item.get("result_url"));
            if (StringUtils.hasText(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    private String buildImageSynthesisFailureMessage(String taskStatus, String code, String message) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(taskStatus)) {
            parts.add("taskStatus=" + taskStatus);
        }
        if (StringUtils.hasText(code)) {
            parts.add("code=" + code);
        }
        if (StringUtils.hasText(message)) {
            parts.add("message=" + message);
        }
        if (parts.isEmpty()) {
            return "未返回明确错误信息";
        }
        return String.join(", ", parts);
    }

    private String normalizeStatus(String taskStatus) {
        return StringUtils.hasText(taskStatus) ? taskStatus.trim().toUpperCase() : "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String resolveDashScopeImageUrl(String imageSource, String model, String tempFilePrefix)
            throws IOException, NoApiKeyException {
        String normalizedSource = imageSource == null ? null : imageSource.trim();
        if (!StringUtils.hasText(normalizedSource)) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        if (isDashScopeDirectImageUrl(normalizedSource)) {
            return normalizedSource;
        }

        ImagePayload imagePayload = parseImagePayload(normalizedSource);
        if (ImageSynthesis.Models.WANX_V1.equals(model)) {
            imagePayload = normalizeReferenceImagePayload(imagePayload);
        }
        Path tempFile = Files.createTempFile(tempFilePrefix, imagePayload.extension());
        try {
            Files.write(tempFile, imagePayload.bytes());
            return OSSUtils.upload(model, tempFile.toAbsolutePath().toString(), tongYiConfig.getApiKey());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String resolveImageEditInput(String imageSource) {
        String normalizedSource = imageSource == null ? null : imageSource.trim();
        if (!StringUtils.hasText(normalizedSource)) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        if (normalizedSource.startsWith("data:image/")) {
            int commaIndex = normalizedSource.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("不合法的 Data URL 图片内容");
            }
            resolveExtensionFromMimeType(extractMimeType(normalizedSource.substring(5, commaIndex)));
            return normalizedSource;
        }
        if (isDashScopeDirectImageUrl(normalizedSource)) {
            return normalizedSource;
        }
        return "data:image/png;base64," + normalizedSource;
    }

    private String resolveQwenImageEditInput(String imageSource) {
        String normalizedSource = resolveImageEditInput(imageSource);
        if (!normalizedSource.startsWith("data:image/")) {
            return normalizedSource;
        }
        if (!normalizedSource.contains(",")) {
            throw new IllegalArgumentException("不合法的 Data URL 图片内容");
        }
        return normalizedSource;
    }

    private String normalizeImageVariantPrompt(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("图生图提示词不能为空");
        }
        return "请对输入图片执行严格编辑，而不是只做轻微润色。必须明显体现以下要求，并确保最终结果与要求一致：" + prompt.trim();
    }

    private String buildImageVariantNegativePrompt(String prompt) {
        String normalizedPrompt = prompt == null ? "" : prompt.trim();
        List<String> items = new ArrayList<>(Arrays.asList(
                "仅做轻微修改",
                "颜色保持不变",
                "仍然是原始风格",
                "低质量",
                "模糊",
                "失真",
                "细节错误"
        ));
        if (normalizedPrompt.contains("黑白") || normalizedPrompt.contains("灰度") || normalizedPrompt.contains("单色")) {
            items.add("彩色");
            items.add("蓝色");
            items.add("红色");
            items.add("高饱和色彩");
        }
        return String.join("，", items);
    }

    private List<String> extractMultiModalImageUrls(MultiModalConversationResult result) {
        if (result == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图生图失败: 返回结果为空");
        }
        if (StringUtils.hasText(result.getCode()) || StringUtils.hasText(result.getMessage())) {
            throw new BusinessException(
                    ResponseCode.SERVICE_UNAVAILABLE,
                    "通义图生图失败: code=" + firstNonBlank(result.getCode(), "UNKNOWN")
                            + ", message=" + firstNonBlank(result.getMessage(), "未返回错误信息")
            );
        }
        if (result.getOutput() == null || result.getOutput().getChoices() == null || result.getOutput().getChoices().isEmpty()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图生图失败: 未返回有效 choices");
        }
        if (result.getOutput().getChoices().get(0).getMessage() == null
                || result.getOutput().getChoices().get(0).getMessage().getContent() == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图生图失败: 未返回有效图片内容");
        }

        List<String> urls = new ArrayList<>();
        for (Map<String, Object> content : result.getOutput().getChoices().get(0).getMessage().getContent()) {
            Object image = content.get("image");
            if (image instanceof String imageUrl && StringUtils.hasText(imageUrl)) {
                urls.add(imageUrl);
            }
        }
        return urls;
    }

    private PreparedImageInput createReferenceImageFile(String imageSource) throws IOException {
        String normalizedSource = imageSource == null ? null : imageSource.trim();
        if (!StringUtils.hasText(normalizedSource)) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        if (normalizedSource.startsWith("file://")) {
            return new PreparedImageInput(normalizedSource, null);
        }
        if (normalizedSource.startsWith("http://") || normalizedSource.startsWith("https://")) {
            return new PreparedImageInput(normalizedSource, null);
        }

        ImagePayload payload = normalizeReferenceImagePayload(parseImagePayload(normalizedSource));
        Path tempFile = Files.createTempFile("pisces-ref-", payload.extension());
        Files.write(tempFile, payload.bytes());
        return new PreparedImageInput(tempFile.toUri().toString(), tempFile);
    }

    private ImagePayload normalizeReferenceImagePayload(ImagePayload imagePayload) throws IOException {
        if (!isSupportedReferenceImageExtension(imagePayload.extension())) {
            throw new IllegalArgumentException("图生图参考图仅支持 JPEG、PNG");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imagePayload.bytes()));
        if (image == null) {
            throw new IllegalArgumentException("图生图参考图无法解析，请上传有效的 JPEG、PNG 图片");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean writeSuccess = ImageIO.write(image, "png", outputStream);
        if (!writeSuccess) {
            throw new IllegalArgumentException("图生图参考图转换失败，请重新上传 JPEG、PNG 图片");
        }
        return new ImagePayload(outputStream.toByteArray(), ".png");
    }

    private boolean isDashScopeDirectImageUrl(String imageSource) {
        return imageSource.startsWith("http://")
                || imageSource.startsWith("https://")
                || imageSource.startsWith("oss://")
                || imageSource.startsWith("file://");
    }

    private void deleteTempFileQuietly(PreparedImageInput preparedImageInput) {
        if (preparedImageInput == null || preparedImageInput.tempFile() == null) {
            return;
        }
        try {
            Files.deleteIfExists(preparedImageInput.tempFile());
        } catch (IOException e) {
            log.warn("删除临时参考图失败: {}", preparedImageInput.tempFile(), e);
        }
    }

    private boolean isSupportedReferenceImageExtension(String extension) {
        return ".jpg".equalsIgnoreCase(extension) || ".png".equalsIgnoreCase(extension);
    }

    private ImagePayload parseImagePayload(String imageSource) {
        String normalized = imageSource.trim();
        String extension = ".png";
        int commaIndex = normalized.indexOf(',');
        if (normalized.startsWith("data:image/")) {
            if (commaIndex < 0) {
                throw new IllegalArgumentException("不合法的 Data URL 图片内容");
            }
            extension = resolveExtensionFromMimeType(extractMimeType(normalized.substring(5, commaIndex)));
            normalized = normalized.substring(commaIndex + 1);
        } else if (commaIndex >= 0) {
            normalized = normalized.substring(commaIndex + 1);
        }
        return new ImagePayload(java.util.Base64.getDecoder().decode(normalized), extension);
    }

    private String extractMimeType(String mimeSegment) {
        int semicolonIndex = mimeSegment.indexOf(';');
        if (semicolonIndex >= 0) {
            return mimeSegment.substring(0, semicolonIndex);
        }
        return mimeSegment;
    }

    private String resolveExtensionFromMimeType(String mimeType) {
        switch (mimeType.toLowerCase()) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/webp":
                return ".webp";
            case "image/bmp":
                return ".bmp";
            case "image/tiff":
            case "image/tif":
                return ".tiff";
            default:
                throw new IllegalArgumentException("不支持的图片格式: " + mimeType + "，当前仅支持 JPEG、PNG、WEBP、BMP、TIFF");
        }
    }

    private record ImagePayload(byte[] bytes, String extension) {
    }

    private record PreparedImageInput(String input, Path tempFile) {
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
            generateExperimentData(experimentId, finalVariants, visitorCount, Math.max(1, daysAgo));
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
            result.put("experimentName", "AI生成变体实验 - " + prompt);
            result.put("variants", analysisResult.get("variants"));
            result.put("analysisSummary", analysisResult.get("summary"));
            
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
    private void generateExperimentData(String experimentId, List<Map<String, Object>> variants, int visitorCount, int daysSpan) {
        Map<String, List<String>> groupVisitors = new HashMap<>();
        
        // 为每个实验组生成访客并分配
        for (int i = 0; i < variants.size(); i++) {
            String groupId = "group_" + (i == 0 ? "A" : String.valueOf((char)('A' + i)));
            List<String> visitors = new ArrayList<>();
            
            int maxAttempts = Math.max(visitorCount * 20, 1000);
            int attempts = 0;
            while (visitors.size() < visitorCount && attempts < maxAttempts) {
                String visitorId = "visitor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                // 分配访客到实验组
                String assignedGroup = trafficService.assignGroup(experimentId, visitorId);
                if (groupId.equals(assignedGroup)) {
                    visitors.add(visitorId);
                }
                attempts++;
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
                LocalDateTime eventTime = randomEventTime(daysSpan);
                Map<String, Object> viewProperties = new HashMap<>();
                viewProperties.put("eventTime", eventTime);
                dataService.reportEvent(experimentId, visitorId, "VIEW", "商品详情页浏览", viewProperties);
                
                // 80%的访客有点击事件
                if (random.nextDouble() < 0.8) {
                    Map<String, Object> clickProperties = new HashMap<>();
                    clickProperties.put("eventTime", eventTime.plusMinutes(random.nextInt(90) + 1));
                    dataService.reportEvent(experimentId, visitorId, "CLICK", "价格咨询点击", clickProperties);
                }
                
                // 根据转化率生成转化事件
                if (random.nextDouble() < conversionRate) {
                    Map<String, Object> convertProperties = new HashMap<>();
                    convertProperties.put("eventTime", eventTime.plusHours(random.nextInt(24) + 1));
                    dataService.reportEvent(experimentId, visitorId, "CONVERT", "成交", convertProperties);
                }
            }
        }
    }

    private LocalDateTime randomEventTime(int daysSpan) {
        return LocalDateTime.now()
                .minusDays(Math.max(0, daysSpan - 1L))
                .plusDays(random.nextInt(Math.max(1, daysSpan)))
                .plusHours(random.nextInt(24))
                .plusMinutes(random.nextInt(60));
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
