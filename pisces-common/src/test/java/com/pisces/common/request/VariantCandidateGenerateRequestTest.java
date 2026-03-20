package com.pisces.common.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

/**
 * 候选变体生成请求验证
 */
class VariantCandidateGenerateRequestTest {

    @Test
    void shouldExposeAllFields() {
        VariantCandidateGenerateRequest request = new VariantCandidateGenerateRequest();
        request.setVariantType("TEXT");
        request.setGoal("提升支付转化率");
        request.setAudience("二手手机购买用户");
        request.setConstraints(List.of("文案简洁", "突出质保"));
        request.setCount(4);
        request.setSourceContext(Map.of("scene", "detail-page"));

        assertThat(request.getVariantType()).isEqualTo("TEXT");
        assertThat(request.getGoal()).isEqualTo("提升支付转化率");
        assertThat(request.getAudience()).isEqualTo("二手手机购买用户");
        assertThat(request.getConstraints()).containsExactly("文案简洁", "突出质保");
        assertThat(request.getCount()).isEqualTo(4);
        assertThat(request.getSourceContext()).containsEntry("scene", "detail-page");
    }
}
