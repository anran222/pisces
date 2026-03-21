package com.pisces.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI决策JSON解析器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 14:09
 */
class AIDecisionJsonParserTest {

    @Test
    void shouldRejectInvalidJsonDecisionPayload() {
        AIDecisionJsonParser parser = new AIDecisionJsonParser(new JsonUtil(new ObjectMapper()));

        assertThatThrownBy(() -> parser.parseGraduation("not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI决策结果不是合法JSON");
    }

    @Test
    void shouldParseGraduationDecisionPayload() {
        AIDecisionJsonParser parser = new AIDecisionJsonParser(new JsonUtil(new ObjectMapper()));
        String json = """
                {
                  "decisionType": "GRADUATION",
                  "summary": "建议继续观察",
                  "confidence": 0.76,
                  "riskFlags": ["sample_size_low"],
                  "guardrailStatus": "PASS",
                  "decision": "CONTINUE"
                }
                """;

        AIGraduationDecisionResponse response = parser.parseGraduation(json);

        assertThat(response.getDecisionType()).isEqualTo("GRADUATION");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getDecision()).isEqualTo("CONTINUE");
        assertThat(response.getRiskFlags()).containsExactly("sample_size_low");
    }

    @Test
    void shouldNormalizeStringConfidenceInGraduationDecisionPayload() {
        AIDecisionJsonParser parser = new AIDecisionJsonParser(new JsonUtil(new ObjectMapper()));
        String json = """
                {
                  "decisionType": "GRADUATE",
                  "summary": "实验结果稳定",
                  "confidence": "HIGH",
                  "riskFlags": [],
                  "guardrailStatus": "PASS",
                  "decision": "GRADUATE"
                }
                """;

        AIGraduationDecisionResponse response = parser.parseGraduation(json);

        assertThat(response.getConfidence()).isEqualTo(0.9D);
        assertThat(response.getDecision()).isEqualTo("GRADUATE");
    }

    @Test
    void shouldNormalizeDiagnosisActionsFromStringArray() {
        AIDecisionJsonParser parser = new AIDecisionJsonParser(new JsonUtil(new ObjectMapper()));
        String json = """
                {
                  "decisionType": "CONTINUE",
                  "summary": "实验状态正常",
                  "confidence": 0.85,
                  "riskFlags": [],
                  "guardrailStatus": "HEALTHY",
                  "recommendedActions": [
                    "继续监控关键指标变化",
                    "确保样本量充足"
                  ]
                }
                """;

        AIDiagnosisResponse response = parser.parseDiagnosis(json);

        assertThat(response.getGuardrailStatus()).isEqualTo("HEALTHY");
        assertThat(response.getRecommendedActions())
                .extracting(AIDiagnosisResponse.RecommendedAction::getAction)
                .containsExactly("继续监控关键指标变化", "确保样本量充足");
    }
}
