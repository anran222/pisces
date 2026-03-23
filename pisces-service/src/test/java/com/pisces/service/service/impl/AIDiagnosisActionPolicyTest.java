package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.AIDesignContextResolver;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI诊断动作策略测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:37
 */
class AIDiagnosisActionPolicyTest {

    private final AIDesignContextResolver aiDesignContextResolver = new AIDesignContextResolver();

    @Test
    void shouldMarkTrafficAdjustmentAsManualOnly() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        ExperimentDecisionContextBuilder contextBuilder = mock(ExperimentDecisionContextBuilder.class);
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                contextBuilder,
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                new DecisionGuardrailEvaluator(),
                tongYiTextGenerationClient(),
                new GroupConfigSchemaValidator(jsonUtil));

        when(contextBuilder.buildForExperiment("exp_1")).thenReturn(blockedContext());

        AIDiagnosisResponse response = service.diagnoseExperiment("exp_1");

        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getExecutionMode)
                .contains("MANUAL_ONLY");
    }

    @Test
    void shouldConvertStringDiagnosisActionsIntoStructuredActions() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        ExperimentDecisionContextBuilder contextBuilder = mock(ExperimentDecisionContextBuilder.class);
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                contextBuilder,
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                new DecisionGuardrailEvaluator(),
                diagnosisStringActionClient(),
                new GroupConfigSchemaValidator(jsonUtil));

        when(contextBuilder.buildForExperiment("exp_1")).thenReturn(blockedContext());

        AIDiagnosisResponse response = service.diagnoseExperiment("exp_1");

        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getAction)
                .containsExactly("继续监控关键指标变化，特别是点击率和转化率", "确保样本量充足以支持后续统计显著性分析");
        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getExecutionMode)
                .containsOnly("MANUAL_ONLY");
    }

    private TongYiTextGenerationClient tongYiTextGenerationClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "decisionType":"DIAGNOSIS",
                  "summary":"发现样本分配异常",
                  "confidence":0.72,
                  "riskFlags":[],
                  "guardrailStatus":"PASS",
                  "recommendedActions":[
                    {
                      "title":"自动调流",
                      "action":"提升实验组流量",
                      "executionMode":"AUTO"
                    }
                  ]
                }
                """);
        return client;
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

    private TongYiTextGenerationClient diagnosisStringActionClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "decisionType":"CONTINUE",
                  "summary":"实验运行状态正常",
                  "confidence":0.85,
                  "riskFlags":[],
                  "guardrailStatus":"HEALTHY",
                  "recommendedActions":[
                    "继续监控关键指标变化，特别是点击率和转化率",
                    "确保样本量充足以支持后续统计显著性分析"
                  ]
                }
                """);
        return client;
    }
}
