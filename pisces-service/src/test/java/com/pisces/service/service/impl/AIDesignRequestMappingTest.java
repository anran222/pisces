package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI实验设计请求映射测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:32
 */
class AIDesignRequestMappingTest {

    @Test
    void shouldIncludeExperimentDraftSkeleton() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(),
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                tongYiTextGenerationClient());
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));

        AIDesignResponse response = service.designExperiment(request);

        assertThat(response.getExperimentDraft()).isNotNull();
        assertThat(response.getExperimentDraft().getGroupConfigSchema()).isNotEmpty();
        assertThat(response.getExperimentDraft().getGroups()).isNotEmpty();
        assertThat(response.getExperimentDraft().getGroupConfigSchema())
                .extracting(field -> field.getKey())
                .contains("mainTitle", "subtitle", "showQualityBadge", "badgeCount", "cardMeta", "highlightTags");
        assertThat(response.getExperimentDraft().getGroups().get(0).getConfig()).isEmpty();
    }

    private TongYiTextGenerationClient tongYiTextGenerationClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenReturn("""
                {
                  "decisionType":"DESIGN",
                  "summary":"建议开展实验",
                  "confidence":0.91,
                  "riskFlags":[],
                  "guardrailStatus":"PASS"
                }
                """);
        return client;
    }
}
