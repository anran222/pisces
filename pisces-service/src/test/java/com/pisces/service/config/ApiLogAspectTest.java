package com.pisces.service.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiLogAspectTest {

    @Test
    @SuppressWarnings("unchecked")
    void maskedHeadersShouldRedactPiscesApiKey() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(
                Collections.singletonList("X-Pisces-Api-Key")));
        when(request.getHeader("X-Pisces-Api-Key")).thenReturn("ops-key-production-secret");

        ApiLogAspect aspect = new ApiLogAspect();
        Method maskedHeaders = ApiLogAspect.class.getDeclaredMethod("maskedHeaders", HttpServletRequest.class);
        maskedHeaders.setAccessible(true);

        Map<String, String> headers = (Map<String, String>) maskedHeaders.invoke(aspect, request);

        assertThat(headers.get("X-Pisces-Api-Key"))
                .isEqualTo("op****et")
                .doesNotContain("ops-key-production-secret");
    }
}
