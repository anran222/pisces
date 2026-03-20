package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                jsonUtil);
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));

        AIDesignResponse response = service.designExperiment(request);

        assertThat(response.getExperimentDraft()).isNotNull();
        assertThat(response.getExperimentDraft().getGroups()).isNotEmpty();
    }
}
