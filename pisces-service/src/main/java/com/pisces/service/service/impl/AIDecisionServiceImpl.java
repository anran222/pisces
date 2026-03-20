package com.pisces.service.service.impl;

import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.ai.DecisionType;
import com.pisces.service.ai.GuardrailStatus;
import com.pisces.service.service.AIDecisionService;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * AI决策服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
@Service
public class AIDecisionServiceImpl implements AIDecisionService {

    private static final String SUMMARY_PREFIX = "AI";

    @Override
    public AIDesignResponse designExperiment(AIDesignRequest request) {
        AIDesignResponse response = new AIDesignResponse();
        response.setDecisionType(DecisionType.DESIGN.getCode());
        response.setSummary(SUMMARY_PREFIX + "实验设计草案");
        response.setConfidence(0.5D);
        response.setRiskFlags(Collections.emptyList());
        response.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        return response;
    }

    @Override
    public AIDiagnosisResponse diagnoseExperiment(String experimentId) {
        AIDiagnosisResponse response = new AIDiagnosisResponse();
        response.setDecisionType(DecisionType.DIAGNOSIS.getCode());
        response.setSummary(SUMMARY_PREFIX + "实验诊断草案");
        response.setConfidence(0.5D);
        response.setRiskFlags(Collections.emptyList());
        response.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        response.setRecommendedActions(Collections.emptyList());
        return response;
    }

    @Override
    public AIGraduationDecisionResponse decideGraduation(String experimentId) {
        AIGraduationDecisionResponse response = new AIGraduationDecisionResponse();
        response.setDecisionType(DecisionType.GRADUATION.getCode());
        response.setSummary(SUMMARY_PREFIX + "毕业决策草案");
        response.setConfidence(0.5D);
        response.setRiskFlags(Collections.emptyList());
        response.setGuardrailStatus(GuardrailStatus.PASS.getCode());
        response.setDecision("CONTINUE");
        return response;
    }
}
