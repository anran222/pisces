package com.pisces.service.security;

import java.util.Arrays;
import java.util.Locale;

/**
 * API Key 权限域
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
public enum ApiKeyScope {

    /**
     * 运行时接口 (Runtime APIs)
     */
    RUNTIME,

    /**
     * 分析接口 (Analysis APIs)
     */
    ANALYSIS,

    /**
     * 管理接口 (Management APIs)
     */
    MANAGEMENT,

    /**
     * 管理员接口 (Admin APIs)
     */
    ADMIN;

    public static ApiKeyScope of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(scope -> scope.name().equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static ApiKeyScope ofOrThrow(String code) {
        ApiKeyScope scope = of(code);
        if (scope == null) {
            throw new IllegalArgumentException("不支持的 API Key 权限域: " + code);
        }
        return scope;
    }
}
