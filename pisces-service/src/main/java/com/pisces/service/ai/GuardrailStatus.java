package com.pisces.service.ai;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 护栏状态 (Guardrail status)
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
@Getter
public enum GuardrailStatus {

    /**
     * 通过
     */
    PASS("PASS", "通过"),

    /**
     * 阻断
     */
    BLOCKED("BLOCKED", "阻断");

    private final String code;
    private final String desc;

    GuardrailStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static GuardrailStatus of(String code) {
        if (code == null) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static GuardrailStatus ofOrThrow(String code) {
        GuardrailStatus guardrailStatus = of(code);
        if (guardrailStatus == null) {
            throw new IllegalArgumentException("不支持的护栏状态: " + code);
        }
        return guardrailStatus;
    }
}
