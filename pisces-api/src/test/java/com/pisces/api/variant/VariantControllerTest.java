package com.pisces.api.variant;

import com.pisces.service.service.VariantGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VariantControllerTest {

    private VariantGenerationService variantGenerationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        variantGenerationService = mock(VariantGenerationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new VariantController(variantGenerationService)).build();
    }

    @Test
    void shouldExposeOnlyTextAndImageGenerationHttpMappings() throws Exception {
        when(variantGenerationService.generateTextVariants("写一个商品标题", 3))
                .thenReturn(List.of("标题A", "标题B", "标题C"));
        when(variantGenerationService.generateImageVariants("生成主图", 2))
                .thenReturn(List.of("https://example.com/a.png"));

        mockMvc.perform(post("/variants/text/generate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("prompt", "写一个商品标题")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("标题A"));

        mockMvc.perform(post("/variants/image/generate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("prompt", "生成主图")
                        .param("count", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("https://example.com/a.png"));

        mockMvc.perform(post("/variants/image/generate-from-image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/image/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/image/style-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/variants/image/download")
                        .param("url", "https://example.com/a.png"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/variants/image/styles"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"A\",\"B\"]"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/evaluate")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("variant", "A")
                        .param("variantType", "TEXT"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/text/demo")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("prompt", "测试"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/variants/experiment/flow")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("prompt", "测试"))
                .andExpect(status().isNotFound());

        verify(variantGenerationService).generateTextVariants(eq("写一个商品标题"), eq(3));
        verify(variantGenerationService).generateImageVariants(eq("生成主图"), eq(2));
    }

    @Test
    void shouldGenerateStructuredTextVariantsThroughAggregateEndpoint() throws Exception {
        when(variantGenerationService.generateTextVariants(anyString(), eq(3)))
                .thenReturn(List.of("文案A", "文案B", "文案C"));
        when(variantGenerationService.getLastTextGenerationMetadata())
                .thenReturn(Map.of(
                        "provider", "tongyi",
                        "primaryModel", "qwen3.8-max-preview",
                        "selectedModel", "qwen3.7-max",
                        "selectedApiMode", "dashscope",
                        "fallbackUsed", true,
                        "fallbackModel", "qwen3.7-max",
                        "attemptedModels", List.of("qwen3.8-max-preview", "qwen3.7-max"),
                        "modelStrategy", "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in"
                ));

        mockMvc.perform(post("/variants/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "variantType": "TEXT",
                                  "goal": "请为二手手机详情页生成3个文案变体",
                                  "audience": "二手手机购买用户",
                                  "constraints": ["突出质保", "语言简洁"],
                                  "count": 3,
                                  "sourceContext": {
                                    "scene": "detail-page"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variantType").value("TEXT"))
                .andExpect(jsonPath("$.data.count").value(3))
                .andExpect(jsonPath("$.data.variants[0]").value("文案A"))
                .andExpect(jsonPath("$.data.aiProvider").value("tongyi"))
                .andExpect(jsonPath("$.data.aiPrimaryModel").value("qwen3.8-max-preview"))
                .andExpect(jsonPath("$.data.aiModel").value("qwen3.7-max"))
                .andExpect(jsonPath("$.data.aiApiMode").value("dashscope"))
                .andExpect(jsonPath("$.data.aiFallbackUsed").value(true))
                .andExpect(jsonPath("$.data.aiFallbackModel").value("qwen3.7-max"))
                .andExpect(jsonPath("$.data.aiAttemptedModels[0]").value("qwen3.8-max-preview"))
                .andExpect(jsonPath("$.data.aiAttemptedModels[1]").value("qwen3.7-max"))
                .andExpect(jsonPath("$.data.aiModelStrategy").value(
                        "production-dashscope-qwen3.7-max-with-token-plan-preview-opt-in"));

        var promptCaptor = forClass(String.class);
        verify(variantGenerationService).generateTextVariants(promptCaptor.capture(), eq(3));
        verify(variantGenerationService).getLastTextGenerationMetadata();
        assertThat(promptCaptor.getValue())
                .contains("变体类型: TEXT")
                .contains("生成目标: 请为二手手机详情页生成3个文案变体")
                .contains("目标受众: 二手手机购买用户")
                .contains("约束条件: 突出质保；语言简洁")
                .contains("上下文信息: scene=detail-page")
                .contains("请生成3个候选变体。");
    }

    @Test
    void shouldGenerateStructuredImageVariantsThroughAggregateEndpoint() throws Exception {
        when(variantGenerationService.generateImageVariants(
                anyString(),
                eq(2),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of("https://example.com/1.png", "https://example.com/2.png"));

        mockMvc.perform(post("/variants/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "variantType": "IMAGE",
                                  "goal": "请为二手手机主图生成2个图片候选",
                                  "audience": "二手手机购买用户",
                                  "constraints": ["白底", "突出成色"],
                                  "count": 2,
                                  "sourceContext": {
                                    "brief": "当前主图背景层级较乱",
                                    "imageUrl": "https://example.com/source.png"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variantType").value("IMAGE"))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.variants[0]").value("https://example.com/1.png"));

        var promptCaptor = forClass(String.class);
        @SuppressWarnings("unchecked")
        var sourceContextCaptor = (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>) (org.mockito.ArgumentCaptor<?>) forClass(java.util.Map.class);
        verify(variantGenerationService).generateImageVariants(promptCaptor.capture(), eq(2), sourceContextCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("变体类型: IMAGE")
                .contains("生成目标: 请为二手手机主图生成2个图片候选")
                .contains("目标受众: 二手手机购买用户")
                .contains("约束条件: 白底；突出成色")
                .contains("上下文信息: brief=当前主图背景层级较乱, referenceImage=provided")
                .contains("请生成2个候选变体。");
        assertThat(sourceContextCaptor.getValue())
                .containsEntry("brief", "当前主图背景层级较乱")
                .containsEntry("imageUrl", "https://example.com/source.png");
    }

    @Test
    void shouldRejectUnsupportedStructuredVariantType() throws Exception {
        mockMvc.perform(post("/variants/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "variantType": "VIDEO",
                                  "goal": "测试",
                                  "count": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不支持的变体类型: VIDEO"));
    }

    @Test
    void shouldRejectNonPositiveCountForAggregateEndpoint() throws Exception {
        mockMvc.perform(post("/variants/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "variantType": "TEXT",
                                  "goal": "测试",
                                  "count": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("count必须大于0"));
    }

    @Test
    void shouldDefaultMissingCountToOneForAggregateEndpoint() throws Exception {
        when(variantGenerationService.generateTextVariants(anyString(), eq(1)))
                .thenReturn(List.of("默认文案"));

        mockMvc.perform(post("/variants/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "variantType": "TEXT",
                                  "goal": "测试"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variantType").value("TEXT"))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.variants[0]").value("默认文案"));

        var promptCaptor = forClass(String.class);
        verify(variantGenerationService).generateTextVariants(promptCaptor.capture(), eq(1));
        assertThat(promptCaptor.getValue())
                .contains("请生成1个候选变体。");
    }
}
