package com.pisces.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;

/**
 * AI决策JSON解析器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:20
 */
@Component
@RequiredArgsConstructor
public class AIDecisionJsonParser {

    private static final String INVALID_JSON_MESSAGE = "AI决策结果不是合法JSON";
    private static final String INVALID_PAYLOAD_MESSAGE = "AI决策结果缺少必要字段";
    private static final String CONFIDENCE_FIELD = "confidence";
    private static final double HIGH_CONFIDENCE_SCORE = 0.9D;
    private static final double MEDIUM_CONFIDENCE_SCORE = 0.6D;
    private static final double LOW_CONFIDENCE_SCORE = 0.3D;
    private static final String RECOMMENDED_ACTIONS_FIELD = "recommendedActions";
    private static final String ACTION_TITLE_FIELD = "title";
    private static final String ACTION_DESCRIPTION_FIELD = "action";
    private static final String ACTION_TITLE_VALUE = "建议动作";

    private final JsonUtil jsonUtil;

    public AIDesignResponse parseDesign(String json) {
        AIDesignResponse response = parse(json, AIDesignResponse.class);
        if (response == null
                || !StringUtils.hasText(response.getDecisionType())
                || !StringUtils.hasText(response.getGuardrailStatus())) {
            throw new IllegalArgumentException(INVALID_PAYLOAD_MESSAGE);
        }
        if (response.getRiskFlags() == null) {
            response.setRiskFlags(Collections.emptyList());
        }
        return response;
    }

    public AIDiagnosisResponse parseDiagnosis(String json) {
        AIDiagnosisResponse response = parse(json, AIDiagnosisResponse.class);
        if (response == null
                || !StringUtils.hasText(response.getDecisionType())
                || !StringUtils.hasText(response.getGuardrailStatus())) {
            throw new IllegalArgumentException(INVALID_PAYLOAD_MESSAGE);
        }
        if (response.getRiskFlags() == null) {
            response.setRiskFlags(Collections.emptyList());
        }
        if (response.getRecommendedActions() == null) {
            response.setRecommendedActions(Collections.emptyList());
        }
        return response;
    }

    public AIGraduationDecisionResponse parseGraduation(String json) {
        AIGraduationDecisionResponse response = parse(json, AIGraduationDecisionResponse.class);
        if (response == null
                || !StringUtils.hasText(response.getDecisionType())
                || !StringUtils.hasText(response.getGuardrailStatus())
                || !StringUtils.hasText(response.getDecision())) {
            throw new IllegalArgumentException(INVALID_PAYLOAD_MESSAGE);
        }
        if (response.getRiskFlags() == null) {
            response.setRiskFlags(Collections.emptyList());
        }
        return response;
    }

    private <T> T parse(String json, Class<T> clazz) {
        try {
            return jsonUtil.toObject(json, clazz);
        } catch (IllegalStateException exception) {
            return parseWithNormalizedConfidence(json, clazz, exception);
        }
    }

    private <T> T parseWithNormalizedConfidence(String json, Class<T> clazz, IllegalStateException originalException) {
        try {
            Map<String, Object> payload = jsonUtil.toObject(json, new TypeReference<Map<String, Object>>() {
            });
            normalizeConfidenceField(payload);
            normalizeDiagnosisActions(payload, clazz);
            return jsonUtil.toObject(jsonUtil.toJson(payload), clazz);
        } catch (IllegalStateException exception) {
            throw new IllegalArgumentException(INVALID_JSON_MESSAGE, originalException);
        }
    }

    private void normalizeConfidenceField(Map<String, Object> payload) {
        Object normalizedConfidence = normalizeConfidence(payload.get(CONFIDENCE_FIELD));
        if (normalizedConfidence == null) {
            throw new IllegalArgumentException(INVALID_JSON_MESSAGE);
        }
        payload.put(CONFIDENCE_FIELD, normalizedConfidence);
    }

    private <T> void normalizeDiagnosisActions(Map<String, Object> payload, Class<T> clazz) {
        if (!AIDiagnosisResponse.class.equals(clazz)) {
            return;
        }
        Object actionPayload = payload.get(RECOMMENDED_ACTIONS_FIELD);
        if (!(actionPayload instanceof List<?> actionList)) {
            return;
        }
        List<Object> normalizedActions = new ArrayList<>();
        for (Object actionItem : actionList) {
            normalizedActions.add(normalizeDiagnosisAction(actionItem));
        }
        payload.put(RECOMMENDED_ACTIONS_FIELD, normalizedActions);
    }

    private Object normalizeDiagnosisAction(Object actionItem) {
        if (actionItem instanceof Map<?, ?>) {
            return actionItem;
        }
        if (!(actionItem instanceof String actionText) || !StringUtils.hasText(actionText)) {
            return Collections.emptyMap();
        }
        Map<String, Object> normalizedAction = new LinkedHashMap<>();
        normalizedAction.put(ACTION_TITLE_FIELD, ACTION_TITLE_VALUE);
        normalizedAction.put(ACTION_DESCRIPTION_FIELD, actionText.trim());
        return normalizedAction;
    }

    private Double normalizeConfidence(Object confidence) {
        if (confidence instanceof Number number) {
            return number.doubleValue();
        }
        if (!(confidence instanceof String confidenceText) || !StringUtils.hasText(confidenceText)) {
            return null;
        }
        String normalizedText = confidenceText.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedText) {
            case "HIGH" -> HIGH_CONFIDENCE_SCORE;
            case "MEDIUM" -> MEDIUM_CONFIDENCE_SCORE;
            case "LOW" -> LOW_CONFIDENCE_SCORE;
            default -> parseNumericConfidence(normalizedText);
        };
    }

    private Double parseNumericConfidence(String confidenceText) {
        try {
            return Double.parseDouble(confidenceText);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
