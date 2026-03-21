package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI决策护栏评估器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:28
 */
@Component
public class DecisionGuardrailEvaluator {

    private static final String DEMO_EXPERIMENT_TAG = "[USED_PHONE_DEMO_";
    private static final String RISK_FLAG_SRM = "SRM";
    private static final String RISK_FLAG_ANALYSIS_NOT_READY = "ANALYSIS_NOT_READY";
    private static final String RISK_FLAG_SAMPLE_SIZE_NOT_REACHED = "SAMPLE_SIZE_NOT_REACHED";
    private static final String SAMPLE_SIZE_BLOCKING_ISSUE_KEYWORD = "样本量不足";

    public GuardrailStatus evaluateDiagnosis(ExperimentDecisionContext context) {
        return evaluate(context);
    }

    public GuardrailStatus evaluateGraduation(ExperimentDecisionContext context) {
        return evaluate(context);
    }

    public List<String> collectRiskFlags(ExperimentDecisionContext context) {
        Statistics.DataQualityCheck dataQualityCheck = extractDataQualityCheck(context);
        if (dataQualityCheck == null) {
            return Collections.emptyList();
        }
        List<String> riskFlags = new ArrayList<>();
        if (Boolean.TRUE.equals(dataQualityCheck.getHasSrm())) {
            riskFlags.add(RISK_FLAG_SRM);
        }
        if (Boolean.FALSE.equals(dataQualityCheck.getAnalysisReady())) {
            riskFlags.add(RISK_FLAG_ANALYSIS_NOT_READY);
        }
        if (Boolean.FALSE.equals(dataQualityCheck.getSampleSizeReached()) && !ignoreSampleSizeForDemo(context)) {
            riskFlags.add(RISK_FLAG_SAMPLE_SIZE_NOT_REACHED);
        }
        if (dataQualityCheck.getBlockingIssues() != null && !dataQualityCheck.getBlockingIssues().isEmpty()) {
            for (String blockingIssue : dataQualityCheck.getBlockingIssues()) {
                if (ignoreSampleSizeForDemo(context)
                        && blockingIssue != null
                        && blockingIssue.contains(SAMPLE_SIZE_BLOCKING_ISSUE_KEYWORD)) {
                    continue;
                }
                riskFlags.add(blockingIssue);
            }
        }
        return riskFlags;
    }

    private GuardrailStatus evaluate(ExperimentDecisionContext context) {
        return collectRiskFlags(context).isEmpty() ? GuardrailStatus.PASS : GuardrailStatus.BLOCKED;
    }

    private Statistics.DataQualityCheck extractDataQualityCheck(ExperimentDecisionContext context) {
        if (context == null || context.getStatistics() == null) {
            return null;
        }
        return context.getStatistics().getDataQualityCheck();
    }

    private boolean ignoreSampleSizeForDemo(ExperimentDecisionContext context) {
        if (context == null || context.getExperimentName() == null) {
            return false;
        }
        return context.getExperimentName().contains(DEMO_EXPERIMENT_TAG);
    }
}
