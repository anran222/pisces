package com.pisces.common.model;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;

/**
 * 实验组配置字段定义
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/21 16:48
 */
@Data
public class GroupConfigFieldDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 字段键
     */
    private String key;

    /**
     * 字段名称
     */
    private String label;

    /**
     * 字段值类型
     */
    private ValueType valueType;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 字段说明
     */
    private String description;

    /**
     * 默认值
     */
    private Object defaultValue;

    /**
     * 配置值类型 (Group config value type)
     */
    public enum ValueType {
        STRING,
        INTEGER,
        BOOLEAN,
        OBJECT,
        JSON;

        public static ValueType of(String code) {
            if (!StringUtils.hasText(code)) {
                return null;
            }
            String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(item -> item.name().equals(normalizedCode))
                    .findFirst()
                    .orElse(null);
        }

        public static ValueType ofOrThrow(String code) {
            ValueType valueType = of(code);
            if (valueType == null) {
                throw new IllegalArgumentException("不支持的配置值类型: " + code);
            }
            return valueType;
        }
    }
}
