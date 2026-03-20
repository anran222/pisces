package com.pisces.api.variant;

import com.pisces.service.service.VariantGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

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
}
