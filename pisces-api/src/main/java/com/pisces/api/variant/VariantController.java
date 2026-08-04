package com.pisces.api.variant;

import com.pisces.common.response.BaseResponse;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.request.VariantCandidateGenerateRequest;
import com.pisces.common.response.VariantCandidateGenerateResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.VariantGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 变体生成控制器（无用户系统版本）
 * AI赋能：使用生成式AI批量产出高质量实验候选变体
 */
@RestController
@RequestMapping("/variants")
@ApiKeyScopeRequired(ApiKeyScope.MANAGEMENT)
@NoTokenRequired
@RequiredArgsConstructor
public class VariantController {

    private static final String GENERATE_SUCCESS_MESSAGE = "生成成功";
    private static final String VARIANT_TYPE_REQUIRED_MESSAGE = "variantType不能为空";
    private static final String VARIANT_COUNT_INVALID_MESSAGE = "count必须大于0";
    private static final Map<String, String> SOURCE_CONTEXT_LABELS = Map.ofEntries(
            Map.entry("appId", "应用标识"),
            Map.entry("applicationName", "应用名称"),
            Map.entry("businessScenario", "业务场景"),
            Map.entry("scene", "业务场景"),
            Map.entry("placement", "投放位置"),
            Map.entry("baseline", "现状基线"),
            Map.entry("hypothesis", "实验假设"),
            Map.entry("primaryMetricKey", "主指标编码"),
            Map.entry("primaryMetric", "主指标"),
            Map.entry("guardrailMetrics", "护栏指标"),
            Map.entry("expectedLift", "预期提升"),
            Map.entry("startTime", "实验开始时间"),
            Map.entry("endTime", "实验结束时间"),
            Map.entry("sellingPoints", "核心卖点"),
            Map.entry("tone", "表达风格"),
            Map.entry("riskGuardrail", "风险护栏")
    );

    private final VariantGenerationService variantGenerationService;

    /**
     * 生成文本类候选变体（商品标题、详情页文案、咨询话术等）
     */
    @PostMapping("/text/generate")
    public BaseResponse<List<String>> generateTextVariants(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "count", defaultValue = "10") int count) {
        List<String> variants = variantGenerationService.generateTextVariants(prompt, count);
        return BaseResponse.of(GENERATE_SUCCESS_MESSAGE, variants);
    }

    /**
     * 生成图像类候选变体（商品主图、详情页配图等）
     */
    @PostMapping("/image/generate")
    public BaseResponse<List<String>> generateImageVariants(
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "count", defaultValue = "5") int count) {
        List<String> imageUrls = variantGenerationService.generateImageVariants(prompt, count);
        return BaseResponse.of(GENERATE_SUCCESS_MESSAGE, imageUrls);
    }

    /**
     * 统一生成候选变体
     */
    @PostMapping("/generate")
    public BaseResponse<VariantCandidateGenerateResponse> generateVariants(
            @RequestBody VariantCandidateGenerateRequest request) {
        if (request == null || !StringUtils.hasText(request.getVariantType())) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, VARIANT_TYPE_REQUIRED_MESSAGE);
        }
        if (request.getCount() != null && request.getCount() <= 0) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, VARIANT_COUNT_INVALID_MESSAGE);
        }

        String normalizedVariantType = request.getVariantType().toUpperCase(Locale.ROOT);
        int count = request.getCount() == null ? 1 : request.getCount();
        String prompt = buildVariantPrompt(request, normalizedVariantType);

        List<String> variants = dispatchGenerateVariants(normalizedVariantType, prompt, count, request.getSourceContext());
        if (variants == null) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, "不支持的变体类型: " + request.getVariantType());
        }

        VariantCandidateGenerateResponse response = new VariantCandidateGenerateResponse();
        response.setVariantType(normalizedVariantType);
        response.setVariants(variants);
        response.setCount(variants.size());
        if ("TEXT".equals(normalizedVariantType)) {
            applyTextGenerationMetadata(response, variantGenerationService.getLastTextGenerationMetadata());
        }
        return BaseResponse.of(GENERATE_SUCCESS_MESSAGE, response);
    }

    private void applyTextGenerationMetadata(VariantCandidateGenerateResponse response, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        response.setAiProvider(asString(metadata.get("provider")));
        response.setAiModel(asString(metadata.get("selectedModel")));
        response.setAiApiMode(asString(metadata.get("selectedApiMode")));
        response.setAiFallbackUsed(asBoolean(metadata.get("fallbackUsed")));
        response.setAiPrimaryModel(asString(metadata.get("primaryModel")));
        response.setAiFallbackModel(asString(metadata.get("fallbackModel")));
        response.setAiAttemptedModels(asStringList(metadata.get("attemptedModels")));
        response.setAiModelStrategy(asString(metadata.get("modelStrategy")));
    }

    private List<String> dispatchGenerateVariants(String normalizedVariantType,
                                                  String prompt,
                                                  int count,
                                                  Map<String, Object> sourceContext) {
        if ("TEXT".equals(normalizedVariantType)) {
            return variantGenerationService.generateTextVariants(prompt, count);
        }
        if ("IMAGE".equals(normalizedVariantType)) {
            return variantGenerationService.generateImageVariants(prompt, count, sourceContext);
        }
        return null;
    }

    private String buildVariantPrompt(VariantCandidateGenerateRequest request, String normalizedVariantType) {
        List<String> lines = new ArrayList<>();
        lines.add("变体类型: " + normalizedVariantType);
        if (StringUtils.hasText(request.getGoal())) {
            lines.add("生成目标: " + request.getGoal());
        }
        if (StringUtils.hasText(request.getAudience())) {
            lines.add("目标受众: " + request.getAudience());
        }
        if (request.getConstraints() != null && !request.getConstraints().isEmpty()) {
            lines.add("约束条件: " + String.join("；", request.getConstraints()));
        }
        if (request.getSourceContext() != null && !request.getSourceContext().isEmpty()) {
            String sourceContextSummary = buildSourceContextSummary(request.getSourceContext());
            if (StringUtils.hasText(sourceContextSummary)) {
                lines.add("上下文信息: " + sourceContextSummary);
            }
        }
        if ("TEXT".equals(normalizedVariantType)) {
            lines.add("完整方案输出格式: 方案名称：...｜策略方向：...｜候选内容：...｜实验假设：...｜实施建议：...｜风险提醒：...");
            lines.add("每个候选必须独占一行并包含全部六个字段；字段内不得使用换行或竖线；候选内容必须可直接投放。");
        } else if ("IMAGE".equals(normalizedVariantType)) {
            lines.add("图片方案必须围绕实验假设和主指标，清晰呈现商品主体与核心卖点，避免无关文字和夸张效果。");
        }
        lines.add("请生成" + (request.getCount() == null ? 1 : request.getCount()) + "个候选变体。");
        return String.join("\n", lines);
    }

    private String buildSourceContextSummary(Map<String, Object> sourceContext) {
        List<String> details = new ArrayList<>();
        Object brief = sourceContext.get("brief");
        if (brief instanceof String briefText && StringUtils.hasText(briefText)) {
            details.add("补充背景=" + briefText.trim());
        }

        String genericContext = sourceContext.entrySet().stream()
                .filter(entry -> !"brief".equals(entry.getKey()))
                .filter(entry -> !"imageUrl".equals(entry.getKey()))
                .filter(entry -> !"imageBase64".equals(entry.getKey()))
                .filter(entry -> !"referenceImages".equals(entry.getKey()))
                .map(entry -> SOURCE_CONTEXT_LABELS.getOrDefault(entry.getKey(), entry.getKey())
                        + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        if (StringUtils.hasText(genericContext)) {
            details.add(genericContext);
        }

        if (hasTextValue(sourceContext.get("imageUrl"))) {
            details.add("referenceImage=provided");
        }
        if (hasTextValue(sourceContext.get("imageBase64"))) {
            details.add("referenceImageBase64=provided");
        }
        Object referenceImages = sourceContext.get("referenceImages");
        if (referenceImages instanceof List<?> images && !images.isEmpty()) {
            details.add("referenceImages=" + images.stream()
                    .filter(this::hasTextValue)
                    .count());
        }

        return String.join(", ", details);
    }

    private boolean hasTextValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return Boolean.valueOf(stringValue);
        }
        return null;
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return null;
    }
}
