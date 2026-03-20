package com.pisces.service.ai;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 决策类型 (Decision type)
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
@Getter
public enum DecisionType {

    /**
     * 实验设计
     */
    DESIGN("DESIGN", "实验设计"),

    /**
     * 实验诊断
     */
    DIAGNOSIS("DIAGNOSIS", "实验诊断"),

    /**
     * 实验毕业
     */
    GRADUATION("GRADUATION", "实验毕业");

    private final String code;
    private final String desc;

    DecisionType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static DecisionType of(String code) {
        if (code == null) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.code.equals(normalizedCode))
                .findFirst()
                .orElse(null);
    }

    public static DecisionType ofOrThrow(String code) {
        DecisionType decisionType = of(code);
        if (decisionType == null) {
            throw new IllegalArgumentException("不支持的决策类型: " + code);
        }
        return decisionType;
    }
}
