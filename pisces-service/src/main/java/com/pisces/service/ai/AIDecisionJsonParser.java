package com.pisces.service.ai;

import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;

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
            throw new IllegalArgumentException(INVALID_JSON_MESSAGE, exception);
        }
    }
}
