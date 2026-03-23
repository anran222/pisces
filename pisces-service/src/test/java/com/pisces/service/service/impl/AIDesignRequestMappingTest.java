package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.service.ai.AIDecisionJsonParser;
import com.pisces.service.ai.AIDesignContextResolver;
import com.pisces.service.ai.DecisionGuardrailEvaluator;
import com.pisces.service.ai.ExperimentDecisionContextBuilder;
import com.pisces.service.ai.PromptTemplateBuilder;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static final String SCHEMA_PLANNING_OPERATION = "AI实验设计-字段规划";
    private static final String DRAFT_FILLING_OPERATION = "AI实验设计-草案填充";
    private static final String REPAIR_FILLING_OPERATION = "AI实验设计-草案补全";

    private final AIDesignContextResolver aiDesignContextResolver = new AIDesignContextResolver();

    @Test
    void shouldBuildDynamicExperimentDraftFromTwoStageAiResponses() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                twoStageClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));
        request.setExistingSchema(List.of(schemaField("mainTitle", "主标题",
                GroupConfigFieldDefinition.ValueType.STRING, true)));
        AIDesignRequest.DesignPreferences designPreferences = new AIDesignRequest.DesignPreferences();
        designPreferences.setExpectedGroupCount(3);
        request.setDesignPreferences(designPreferences);

        AIDesignResponse response = service.designExperiment(request);

        assertThat(response.getDecisionType()).isEqualTo("DESIGN");
        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getSchemaPlanning()).containsEntry("baselineMode", "REUSE");
        assertThat(response.getDraftGeneration()).containsKey("filledGroups");
        assertThat(response.getExperimentDraft()).isNotNull();
        assertThat(response.getExperimentDraft().getGroupConfigSchema())
                .extracting(GroupConfigFieldDefinition::getKey)
                .containsExactly("mainTitle", "qualityTone", "benefitTags",
                        "subtitle", "showQualityBadge", "badgeCount");
        assertThat(response.getExperimentDraft().getGroups())
                .extracting(group -> group.getId())
                .containsExactly("group_1", "group_2", "group_3");
        assertThat(response.getExperimentDraft().getGroups().get(0).getConfig())
                .containsEntry("mainTitle", "官方质检二手手机")
                .containsEntry("qualityTone", "稳重可信")
                .containsEntry("subtitle", "官方质检 放心下单");
        assertThat(response.getExperimentDraft().getGroups().get(1).getConfig().keySet())
                .containsExactlyInAnyOrder("mainTitle", "qualityTone", "benefitTags",
                        "subtitle", "showQualityBadge", "badgeCount");
        assertThat(response.getExperimentDraft().getTraffic().getStrategy()).isEqualTo("HASH");
    }

    @Test
    void shouldThrowWhenVariantConfigDoesNotCoverFullSchema() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                incompleteDraftClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));

        assertThatThrownBy(() -> service.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("缺少完整配置字段");
    }

    @Test
    void shouldThrowWhenSchemaPlanningUsesSnakeCaseFieldKey() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                snakeCaseSchemaClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));

        assertThatThrownBy(() -> service.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("camelCase");
    }

    @Test
    void shouldThrowWhenSchemaPlanningContainsIdentifierLikeField() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                identifierLikeSchemaClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));

        assertThatThrownBy(() -> service.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("标识/引用型字段");
    }

    @Test
    void shouldUseAiGeneratedGroupCountWhenNoPresetGroupCount() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                twoStageClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));
        request.setExistingSchema(List.of(schemaField("mainTitle", "主标题",
                GroupConfigFieldDefinition.ValueType.STRING, true)));

        AIDesignResponse response = service.designExperiment(request);

        assertThat(response.getGuardrailStatus()).isEqualTo("PASS");
        assertThat(response.getExperimentDraft()).isNotNull();
        assertThat(response.getExperimentDraft().getGroups())
                .extracting(group -> group.getId())
                .containsExactly("group_1", "group_2", "group_3");
    }

    @Test
    void shouldThrowWhenDraftReliesOnRepairFilling() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                repairableDraftClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setConstraints(List.of("保护毛利率"));
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));

        assertThatThrownBy(() -> service.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("缺少完整配置字段");
    }

    @Test
    void shouldThrowWhenAiGeneratedGroupsBelowMinimum() {
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper());
        AIDecisionServiceImpl service = new AIDecisionServiceImpl(
                mock(ExperimentDecisionContextBuilder.class),
                new PromptTemplateBuilder(aiDesignContextResolver),
                aiDesignContextResolver,
                new AIDecisionJsonParser(jsonUtil),
                mock(DecisionGuardrailEvaluator.class),
                oneGroupDraftClient(),
                new GroupConfigSchemaValidator(jsonUtil));
        AIDesignRequest request = new AIDesignRequest();
        request.setBusinessScenario("二手手机详情页");
        request.setTargetMetric("支付转化率");
        request.setBaselineConfig(Map.of("mainTitle", "官方质检二手手机"));

        assertThatThrownBy(() -> service.designExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("实验组数量不能少于 2 个");
    }

    private TongYiTextGenerationClient twoStageClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议先围绕标题语气和利益点标签做实验",
                          "confidence":0.94,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "baselineMode":"REUSE",
                            "groupConfigSchema":[
                              {
                                "key":"mainTitle",
                                "label":"主标题",
                                "valueType":"STRING",
                                "required":true,
                                "description":"商品主标题",
                                "fieldRole":"BASELINE_STABLE"
                              },
                              {
                                "key":"qualityTone",
                                "label":"质检语气",
                                "valueType":"STRING",
                                "required":true,
                                "description":"质检背书表达方式",
                                "fieldRole":"EXPERIMENT_VARIABLE"
                              },
                              {
                                "key":"benefitTags",
                                "label":"利益点标签",
                                "valueType":"JSON",
                                "required":true,
                                "description":"展示给用户的利益点标签",
                                "fieldRole":"EXPERIMENT_VARIABLE"
                              },
                              {
                                "key":"subtitle",
                                "label":"副标题",
                                "valueType":"STRING",
                                "required":true,
                                "description":"补充质检和履约信息",
                                "fieldRole":"EXPERIMENT_VARIABLE"
                              },
                              {
                                "key":"showQualityBadge",
                                "label":"展示质检标识",
                                "valueType":"BOOLEAN",
                                "required":true,
                                "description":"是否展示质检标识",
                                "fieldRole":"EXPERIMENT_VARIABLE"
                              },
                              {
                                "key":"badgeCount",
                                "label":"标签数量",
                                "valueType":"INTEGER",
                                "required":true,
                                "description":"展示标签数量",
                                "fieldRole":"EXPERIMENT_VARIABLE"
                              }
                            ]
                          }
                        }
                        """;
            }
            if (DRAFT_FILLING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议先做标题语气和利益点标签实验",
                          "confidence":0.91,
                          "riskFlags":["BASELINE_REUSED"],
                          "guardrailStatus":"PASS",
                          "draftGeneration":{
                            "filledGroups":[
                              {"groupId":"group_1","role":"CONTROL","name":"对照组"},
                              {"groupId":"group_2","role":"VARIANT","name":"实验组A"},
                              {"groupId":"group_3","role":"VARIANT","name":"实验组B"}
                            ],
                            "controlConfig":{
                              "mainTitle":"平台建议覆盖标题",
                              "qualityTone":"稳重可信",
                              "benefitTags":["官方质检","7天无理由"],
                              "subtitle":"官方质检 放心下单",
                              "showQualityBadge":true,
                              "badgeCount":2
                            },
                            "variantConfigs":{
                              "group_2":{
                                "mainTitle":"质检二手手机放心买",
                                "qualityTone":"强背书",
                                "benefitTags":["官方质检","品质保障"],
                                "subtitle":"官方质检 顺丰发货",
                                "showQualityBadge":true,
                                "badgeCount":2
                              },
                              "group_3":{
                                "mainTitle":"官方严选二手手机",
                                "qualityTone":"简洁专业",
                                "benefitTags":["官方质检","极速发货"],
                                "subtitle":"官方质检 极速发货",
                                "showQualityBadge":true,
                                "badgeCount":3
                              }
                            }
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private TongYiTextGenerationClient incompleteDraftClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议围绕标题与标签做实验",
                          "confidence":0.90,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "groupConfigSchema":[
                              {
                                "key":"mainTitle",
                                "label":"主标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"benefitTags",
                                "label":"利益点标签",
                                "valueType":"JSON",
                                "required":true
                              },
                              {
                                "key":"qualityTone",
                                "label":"质检语气",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"subtitle",
                                "label":"副标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"showQualityBadge",
                                "label":"展示质检标识",
                                "valueType":"BOOLEAN",
                                "required":true
                              },
                              {
                                "key":"badgeCount",
                                "label":"标签数量",
                                "valueType":"INTEGER",
                                "required":true
                              }
                            ]
                          }
                        }
                        """;
            }
            if (DRAFT_FILLING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议先做主标题实验",
                          "confidence":0.84,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "draftGeneration":{
                            "filledGroups":["control","variant_a"],
                            "controlConfig":{
                              "mainTitle":"官方质检二手手机",
                              "benefitTags":["官方质检","7天无理由"],
                              "qualityTone":"稳重可信",
                              "subtitle":"官方质检 放心下单",
                              "showQualityBadge":true,
                              "badgeCount":2
                            },
                            "variantConfigs":{
                              "variant_a":{
                                "mainTitle":"放心买官方质检二手手机",
                                "qualityTone":"强背书",
                                "subtitle":"官方质检 顺丰发货",
                                "showQualityBadge":true,
                                "badgeCount":2
                              }
                            }
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private TongYiTextGenerationClient snakeCaseSchemaClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议围绕标题和标签做实验",
                          "confidence":0.92,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "groupConfigSchema":[
                              {
                                "key":"main_title",
                                "label":"主标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"qualityTone",
                                "label":"质检语气",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"benefitTags",
                                "label":"利益点标签",
                                "valueType":"JSON",
                                "required":true
                              },
                              {
                                "key":"subtitle",
                                "label":"副标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"showQualityBadge",
                                "label":"展示质检标识",
                                "valueType":"BOOLEAN",
                                "required":true
                              },
                              {
                                "key":"badgeCount",
                                "label":"标签数量",
                                "valueType":"INTEGER",
                                "required":true
                              }
                            ]
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private TongYiTextGenerationClient identifierLikeSchemaClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议围绕标题和标签做实验",
                          "confidence":0.92,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "groupConfigSchema":[
                              {
                                "key":"mainTitle",
                                "label":"主标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"titleTemplateId",
                                "label":"标题模板ID",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"qualityTone",
                                "label":"质检语气",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"benefitTags",
                                "label":"利益点标签",
                                "valueType":"JSON",
                                "required":true
                              },
                              {
                                "key":"subtitle",
                                "label":"副标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"showQualityBadge",
                                "label":"展示质检标识",
                                "valueType":"BOOLEAN",
                                "required":true
                              }
                            ]
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private TongYiTextGenerationClient oneGroupDraftClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议围绕标题与标签做实验",
                          "confidence":0.90,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "groupConfigSchema":[
                              {
                                "key":"mainTitle",
                                "label":"主标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"benefitTags",
                                "label":"利益点标签",
                                "valueType":"JSON",
                                "required":true
                              },
                              {
                                "key":"qualityTone",
                                "label":"质检语气",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"subtitle",
                                "label":"副标题",
                                "valueType":"STRING",
                                "required":true
                              },
                              {
                                "key":"showQualityBadge",
                                "label":"展示质检标识",
                                "valueType":"BOOLEAN",
                                "required":true
                              },
                              {
                                "key":"badgeCount",
                                "label":"标签数量",
                                "valueType":"INTEGER",
                                "required":true
                              }
                            ]
                          }
                        }
                        """;
            }
            if (DRAFT_FILLING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议先做单组实验",
                          "confidence":0.70,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "draftGeneration":{
                            "filledGroups":["control"],
                            "controlConfig":{
                              "mainTitle":"官方质检二手手机",
                              "benefitTags":["官方质检","7天无理由"],
                              "qualityTone":"稳重可信",
                              "subtitle":"官方质检 放心下单",
                              "showQualityBadge":true,
                              "badgeCount":2
                            },
                            "variantConfigs":{}
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private TongYiTextGenerationClient repairableDraftClient() {
        TongYiTextGenerationClient client = mock(TongYiTextGenerationClient.class);
        when(client.generateText(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String operation = invocation.getArgument(2, String.class);
            if (SCHEMA_PLANNING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议围绕标题和标签做实验",
                          "confidence":0.94,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "schemaPlanning":{
                            "groupConfigSchema":[
                              {"key":"mainTitle","label":"主标题","valueType":"STRING","required":true},
                              {"key":"titleText","label":"标题文案","valueType":"STRING","required":true},
                              {"key":"benefitTags","label":"利益点标签","valueType":"JSON","required":true},
                              {"key":"qualityTone","label":"质检语气","valueType":"STRING","required":true},
                              {"key":"showQualityBadge","label":"展示质检标识","valueType":"BOOLEAN","required":true},
                              {"key":"badgeCount","label":"标签数量","valueType":"INTEGER","required":true}
                            ]
                          }
                        }
                        """;
            }
            if (DRAFT_FILLING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"建议先做标题实验",
                          "confidence":0.85,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "draftGeneration":{
                            "filledGroups":[
                              {"groupId":"control","role":"CONTROL","name":"对照组"},
                              {"groupId":"variant_a","role":"VARIANT","name":"实验组A"}
                            ],
                            "controlConfig":{
                              "mainTitle":"官方质检二手手机",
                              "benefitTags":["官方质检","7天无理由"],
                              "qualityTone":"稳重可信",
                              "showQualityBadge":true,
                              "badgeCount":2
                            },
                            "variantConfigs":{
                              "variant_a":{
                                "mainTitle":"放心买官方质检二手手机",
                                "benefitTags":["官方质检","品质保障"],
                                "qualityTone":"强背书",
                                "showQualityBadge":true,
                                "badgeCount":2
                              }
                            }
                          }
                        }
                        """;
            }
            if (REPAIR_FILLING_OPERATION.equals(operation)) {
                return """
                        {
                          "decisionType":"DESIGN",
                          "summary":"已补齐缺失字段",
                          "confidence":0.88,
                          "riskFlags":[],
                          "guardrailStatus":"PASS",
                          "draftGeneration":{
                            "controlConfig":{
                              "titleText":"官方质检 放心下单"
                            },
                            "variantConfigs":{
                              "variant_a":{
                                "titleText":"官方质检 顺丰发货"
                              }
                            }
                          }
                        }
                        """;
            }
            throw new IllegalArgumentException("unexpected operation: " + operation);
        });
        return client;
    }

    private GroupConfigFieldDefinition schemaField(String key,
                                                   String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        return fieldDefinition;
    }
}
