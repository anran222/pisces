package com.pisces.api.logging;

import com.pisces.service.config.ApiBodyLogFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApiBodyLogFilterTest {

    private final ApiBodyLogFilter apiBodyLogFilter = new ApiBodyLogFilter();

    @Test
    void bytesToStringShouldUseUtf8ForJsonEvenWhenResponseCharsetFallsBackToLatin1() {
        byte[] bytes = "操作成功".getBytes(StandardCharsets.UTF_8);

        String decoded = (String) ReflectionTestUtils.invokeMethod(
                apiBodyLogFilter,
                "bytesToString",
                bytes,
                StandardCharsets.ISO_8859_1.name(),
                "application/json"
        );

        assertThat(decoded).isEqualTo("操作成功");
    }
}
