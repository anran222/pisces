package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.request.AIDesignRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt模板构建器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:09
 */
class PromptTemplateBuilderTest {

    @Test
    void shouldBuildDesignPromptWithStructuredOutputConstraint() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder();
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("checkout");
        request.setTargetMetric("conversion");
        request.setConstraints(List.of("avoid revenue regression", "protect user experience"));

        String prompt = builder.buildDesignPrompt(request);

        assertThat(prompt).contains("checkout");
        assertThat(prompt).contains("conversion");
        assertThat(prompt).contains("avoid revenue regression");
        assertThat(prompt).contains("JSON");
        assertThat(prompt).contains("decisionType");
        assertThat(prompt).contains("guardrailStatus");
    }

    @Test
    void shouldBuildGraduationPromptWithExperimentContext() {
        PromptTemplateBuilder builder = new PromptTemplateBuilder();
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId("exp_001");
        context.setExperimentName("新客首单优惠");
        context.setExperimentStatus("RUNNING");

        String prompt = builder.buildGraduationPrompt(context);

        assertThat(prompt).contains("exp_001");
        assertThat(prompt).contains("新客首单优惠");
        assertThat(prompt).contains("RUNNING");
        assertThat(prompt).contains("decision");
    }
}
