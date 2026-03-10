package com.pisces.api.logging;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiLogAspectTest {

    private final ApiLogAspect apiLogAspect = new ApiLogAspect();

    @Test
    void maskedArgsShouldKeepNullValuesWithoutThrowing() {
        Object result = ReflectionTestUtils.invokeMethod(
                apiLogAspect,
                "maskedArgs",
                new Object[]{new Object[]{null, "token-value"}, new String[]{"status", "token"}}
        );

        assertThat(result).isInstanceOf(List.class);
        Map<String, Object> nullableStatus = new LinkedHashMap<>();
        nullableStatus.put("status", null);
        Map<String, Object> maskedToken = new LinkedHashMap<>();
        maskedToken.put("token", "***");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actual = (List<Map<String, Object>>) result;
        assertThat(actual).containsExactlyElementsOf(List.of(nullableStatus, maskedToken));
    }
}
