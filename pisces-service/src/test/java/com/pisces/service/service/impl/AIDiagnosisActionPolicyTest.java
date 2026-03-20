package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI诊断动作策略测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:37
 */
class AIDiagnosisActionPolicyTest {

    @Test
    void shouldMarkTrafficAdjustmentAsManualOnly() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        ExperimentDecisionContextBuilder contextBuilder = mock(ExperimentDecisionContextBuilder.class);
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                contextBuilder,
                new PromptTemplateBuilder(),
                new AIDecisionJsonParser(jsonUtil),
                new DecisionGuardrailEvaluator(),
                jsonUtil);

        when(contextBuilder.buildForExperiment("exp_1")).thenReturn(blockedContext());

        AIDiagnosisResponse response = service.diagnoseExperiment("exp_1");

        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getExecutionMode)
                .contains("MANUAL_ONLY");
    }

    private ExperimentDecisionContext blockedContext() {
        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        dataQualityCheck.setHasSrm(true);

        Statistics statistics = new Statistics();
        statistics.setExperimentId("exp_1");
        statistics.setExperimentName("新客首单优惠");
        statistics.setDataQualityCheck(dataQualityCheck);

        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_1");
        context.setExperimentName("新客首单优惠");
        context.setStatistics(statistics);
        return context;
    }
}
