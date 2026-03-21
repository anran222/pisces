package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI决策护栏评估器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:26
 */
class DecisionGuardrailEvaluatorTest {

    @Test
    void shouldDowngradeGraduationWhenSrmFails() {
        DecisionGuardrailEvaluator evaluator = new DecisionGuardrailEvaluator();

        GuardrailStatus status = evaluator.evaluateGraduation(context(true, true, true));

        assertThat(status).isEqualTo(GuardrailStatus.BLOCKED);
    }

    @Test
    void shouldPassGraduationWhenDataQualityReady() {
        DecisionGuardrailEvaluator evaluator = new DecisionGuardrailEvaluator();

        GuardrailStatus status = evaluator.evaluateGraduation(context(false, true, true));

        assertThat(status).isEqualTo(GuardrailStatus.PASS);
    }

    @Test
    void shouldPassGraduationWhenDemoOnlyMissesSampleSize() {
        DecisionGuardrailEvaluator evaluator = new DecisionGuardrailEvaluator();
        ExperimentDecisionContext context = context(false, true, false);
        context.setExperimentName("二手手机售卖页优化实验 [USED_PHONE_DEMO_PASS]");

        GuardrailStatus status = evaluator.evaluateGraduation(context);

        assertThat(status).isEqualTo(GuardrailStatus.PASS);
        assertThat(evaluator.collectRiskFlags(context)).doesNotContain("SAMPLE_SIZE_NOT_REACHED");
    }

    private ExperimentDecisionContext context(boolean hasSrm, boolean analysisReady, boolean sampleSizeReached) {
        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        dataQualityCheck.setHasSrm(hasSrm);
        dataQualityCheck.setAnalysisReady(analysisReady);
        dataQualityCheck.setSampleSizeReached(sampleSizeReached);

        Statistics statistics = new Statistics();
        statistics.setDataQualityCheck(dataQualityCheck);

        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setStatistics(statistics);
        return context;
    }
}
