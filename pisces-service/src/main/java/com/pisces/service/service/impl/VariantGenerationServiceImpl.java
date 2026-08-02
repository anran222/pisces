package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.VariantGenerationService;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 变体生成服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/18 17:03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariantGenerationServiceImpl implements VariantGenerationService {

    private static final String TEXT_SYSTEM_PROMPT = "你是一个专业的文案生成助手，擅长生成多样化、高质量的商业文案。";
    private static final String TEXT_OPERATION_NAME = "通义文本生成";
    private static final String IMAGE_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation";
    private static final String IMAGE_SIZE = "1280*1280";
    private static final String IMAGE_EDIT_SIZE = "1K";
    private static final String IMAGE_NEGATIVE_PROMPT = "";
    private static final int MAX_IMAGE_COUNT = 4;
    private static final int HTTP_CONNECT_TIMEOUT_SECONDS = 30;
    private static final int HTTP_REQUEST_TIMEOUT_SECONDS = 60;
    private static final int HTTP_STATUS_OK = 200;
    private static final int IMAGE_POLL_INTERVAL_SECONDS = 3;
    private static final int IMAGE_MAX_WAIT_SECONDS = 120;
    private static final int MIN_VARIANT_TEXT_LENGTH = 10;
    private static final String TEXT_VARIANT_FAILURE_MESSAGE = "通义文本生成未返回有效变体";
    private static final String IMAGE_VARIANT_FAILURE_MESSAGE = "通义图像生成未返回有效图片";

    private final TongYiConfig tongYiConfig;
    private final JsonUtil jsonUtil;
    private final TongYiTextGenerationClient tongYiTextGenerationClient;

    @Override
    public List<String> generateTextVariants(String prompt, int count) {
        log.info("生成文本变体: prompt={}, count={}", prompt, count);
        ensureTongYiAvailable();

        try {
            String responseText = tongYiTextGenerationClient.generateText(
                    TEXT_SYSTEM_PROMPT,
                    buildStructuredPrompt(prompt, count),
                    TEXT_OPERATION_NAME
            );
            List<String> variants = parseTextVariants(responseText, count);
            if (variants.isEmpty()) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, TEXT_VARIANT_FAILURE_MESSAGE);
            }
            return variants;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义文本生成失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, Object> getLastTextGenerationMetadata() {
        TongYiTextGenerationClient.TextGenerationInvocationSummary invocationSummary =
                tongYiTextGenerationClient.getLastInvocationSummary();
        if (invocationSummary == null) {
            return Map.of();
        }
        return invocationSummary.toMetadataMap();
    }

    @Override
    public List<String> generateImageVariants(String prompt, int count) {
        return generateImageVariants(prompt, count, Collections.emptyMap());
    }

    @Override
    public List<String> generateImageVariants(String prompt, int count, Map<String, Object> sourceContext) {
        log.info("生成图像变体: prompt={}, count={}", prompt, count);
        ensureTongYiAvailable();

        try {
            List<String> imageUrls = callTongYiImageApi(buildImagePrompt(prompt), count, sourceContext);
            if (imageUrls.isEmpty()) {
                throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, IMAGE_VARIANT_FAILURE_MESSAGE);
            }
            return imageUrls;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义图像生成失败: " + exception.getMessage(), exception);
        }
    }

    private String buildStructuredPrompt(String originalPrompt, int count) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("你是一个专业的文案生成助手。请根据以下要求生成").append(count).append("个不同的文案变体。\n\n");
        stringBuilder.append("原始需求：").append(originalPrompt).append("\n\n");
        stringBuilder.append("要求：\n");
        stringBuilder.append("1. 每个变体都要有独特的表达方式，避免重复\n");
        stringBuilder.append("2. 文案要简洁有力，突出核心卖点\n");
        stringBuilder.append("3. 符合商业规范，不包含违规内容\n");
        stringBuilder.append("4. 每个变体长度控制在50-200字之间\n\n");
        stringBuilder.append("请直接输出").append(count).append("个文案变体，每个变体一行，用换行符分隔。");
        return stringBuilder.toString();
    }

    private List<String> parseTextVariants(String responseText, int count) {
        List<String> variants = Arrays.stream(responseText.split("\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(variant -> variant.length() >= MIN_VARIANT_TEXT_LENGTH)
                .limit(count)
                .collect(Collectors.toList());
        if (variants.size() >= count) {
            return variants;
        }

        String[] numberedParts = responseText.split("\\d+[.、]");
        if (numberedParts.length > 1) {
            variants = Arrays.stream(numberedParts)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .filter(variant -> variant.length() >= MIN_VARIANT_TEXT_LENGTH)
                    .limit(count)
                    .collect(Collectors.toList());
        }
        if (variants.size() >= count) {
            return variants;
        }

        return Arrays.stream(responseText.split("[。！？]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(variant -> variant.length() >= MIN_VARIANT_TEXT_LENGTH)
                .limit(count)
                .collect(Collectors.toList());
    }

    private String buildImagePrompt(String originalPrompt) {
        return "高质量商业产品图片，" + originalPrompt + "，专业摄影，清晰细节，商业级品质，白色背景";
    }

    private List<String> callTongYiImageApi(String prompt, int count, Map<String, Object> sourceContext) throws Exception {
        String apiKey = tongYiConfig.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key未配置");
        }

        Map<String, Object> requestBody = buildImageRequestBody(prompt, count, sourceContext);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_SECONDS))
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(IMAGE_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(jsonUtil.toJson(requestBody)))
                .timeout(Duration.ofSeconds(HTTP_REQUEST_TIMEOUT_SECONDS))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_STATUS_OK) {
            log.error("图像生成API返回错误: status={}, body={}", response.statusCode(), response.body());
            return List.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = jsonUtil.toObject(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
        if (output == null) {
            return List.of();
        }
        String taskId = (String) output.get("task_id");
        String taskStatus = (String) output.get("task_status");
        if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
            return waitForImageGenerationResult(apiKey, taskId);
        }
        if ("SUCCEEDED".equals(taskStatus)) {
            return extractImageUrls(output);
        }
        return List.of();
    }

    private List<String> waitForImageGenerationResult(String apiKey, String taskId) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_SECONDS))
                .build();
        String statusUrl = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;
        int waitedSeconds = 0;
        while (waitedSeconds < IMAGE_MAX_WAIT_SECONDS) {
            try {
                Thread.sleep(IMAGE_POLL_INTERVAL_SECONDS * 1000L);
                waitedSeconds += IMAGE_POLL_INTERVAL_SECONDS;
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != HTTP_STATUS_OK) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> responseMap = jsonUtil.toObject(response.body(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) responseMap.get("output");
                if (output == null) {
                    continue;
                }
                String taskStatus = (String) output.get("task_status");
                if ("SUCCEEDED".equals(taskStatus)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> taskResult = (Map<String, Object>) responseMap.get("output");
                    return extractImageUrls(taskResult);
                }
                if ("FAILED".equals(taskStatus)) {
                    return List.of();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception exception) {
                log.error("查询任务状态失败", exception);
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> extractImageUrls(Map<String, Object> output) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        if (results != null && !results.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (Map<String, Object> result : results) {
                Object url = result.get("url");
                if (url instanceof String imageUrl && StringUtils.hasText(imageUrl)) {
                    urls.add(imageUrl);
                }
            }
            if (!urls.isEmpty()) {
                return urls;
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (Map<String, Object> choice : choices) {
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            if (message == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
            if (content == null || content.isEmpty()) {
                continue;
            }
            for (Map<String, Object> item : content) {
                Object image = item.get("image");
                if (image instanceof String imageUrl && StringUtils.hasText(imageUrl)) {
                    urls.add(imageUrl);
                }
            }
        }
        return urls;
    }

    private Map<String, Object> buildImageRequestBody(String prompt, int count, Map<String, Object> sourceContext) {
        List<String> referenceImages = extractReferenceImages(sourceContext);
        if (referenceImages.isEmpty()) {
            return buildTextToImageRequestBody(prompt, count);
        }
        return buildImageEditRequestBody(prompt, count, referenceImages);
    }

    private Map<String, Object> buildTextToImageRequestBody(String prompt, int count) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", tongYiConfig.getImageGenerationModel());
        requestBody.put("input", Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("text", prompt))
                ))
        ));
        requestBody.put("parameters", Map.of(
                "n", Math.min(MAX_IMAGE_COUNT, count),
                "size", IMAGE_SIZE,
                "negative_prompt", IMAGE_NEGATIVE_PROMPT,
                "prompt_extend", true,
                "watermark", false
        ));
        return requestBody;
    }

    private Map<String, Object> buildImageEditRequestBody(String prompt, int count, List<String> referenceImages) {
        List<Map<String, String>> content = new ArrayList<>();
        referenceImages.stream()
                .limit(MAX_IMAGE_COUNT)
                .forEach(referenceImage -> content.add(Map.of("image", referenceImage)));
        content.add(Map.of("text", prompt));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", tongYiConfig.getImageEditModel());
        requestBody.put("input", Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", content
                ))
        ));
        requestBody.put("parameters", Map.of(
                "n", Math.min(MAX_IMAGE_COUNT, count),
                "size", IMAGE_EDIT_SIZE,
                "negative_prompt", IMAGE_NEGATIVE_PROMPT,
                "prompt_extend", true,
                "enable_interleave", false,
                "watermark", false
        ));
        return requestBody;
    }

    private List<String> extractReferenceImages(Map<String, Object> sourceContext) {
        if (sourceContext == null || sourceContext.isEmpty()) {
            return List.of();
        }

        List<String> referenceImages = new ArrayList<>();
        addReferenceImage(referenceImages, sourceContext.get("imageUrl"));
        addReferenceImage(referenceImages, sourceContext.get("imageBase64"));

        Object referenceImagesValue = sourceContext.get("referenceImages");
        if (referenceImagesValue instanceof List<?> images) {
            images.forEach(image -> addReferenceImage(referenceImages, image));
        }

        return referenceImages.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(MAX_IMAGE_COUNT)
                .collect(Collectors.toList());
    }

    private void addReferenceImage(List<String> referenceImages, Object candidate) {
        if (candidate instanceof String imageValue && StringUtils.hasText(imageValue)) {
            referenceImages.add(imageValue.trim());
        }
    }

    private void ensureTongYiAvailable() {
        if (!tongYiConfig.isEnabled()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI未启用，无法执行真实AI流程");
        }
        if (!StringUtils.hasText(tongYiConfig.getApiKey())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置 TONGYI_API_KEY，无法执行真实AI流程");
        }
    }
}
