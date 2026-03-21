package com.pisces.service.schema;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实验组配置 schema 校验器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/21 16:48
 */
@Component
@RequiredArgsConstructor
public class GroupConfigSchemaValidator {

    private static final String VALUE_TYPE_SUFFIX = "值类型不匹配";
    private static final String DEFAULT_VALUE_SCOPE = "schema默认值";

    private final JsonUtil jsonUtil;

    public List<GroupConfigFieldDefinition> normalizeSchema(List<GroupConfigFieldDefinition> schema) {
        if (schema == null || schema.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> schemaKeys = new HashSet<>();
        List<GroupConfigFieldDefinition> normalizedSchema = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            if (fieldDefinition == null) {
                throw validationError("实验组配置字段定义不能为空");
            }
            String fieldKey = normalizeText(fieldDefinition.getKey());
            if (!StringUtils.hasText(fieldKey)) {
                throw validationError("实验组配置字段 key 不能为空");
            }
            if (!schemaKeys.add(fieldKey)) {
                throw validationError("实验组配置字段 key 重复: " + fieldKey);
            }
            String fieldLabel = normalizeText(fieldDefinition.getLabel());
            if (!StringUtils.hasText(fieldLabel)) {
                throw validationError("实验组配置字段 label 不能为空: " + fieldKey);
            }
            if (fieldDefinition.getValueType() == null) {
                throw validationError("实验组配置字段 valueType 不能为空: " + fieldKey);
            }

            GroupConfigFieldDefinition normalizedField = new GroupConfigFieldDefinition();
            normalizedField.setKey(fieldKey);
            normalizedField.setLabel(fieldLabel);
            normalizedField.setValueType(fieldDefinition.getValueType());
            normalizedField.setRequired(Boolean.TRUE.equals(fieldDefinition.getRequired()));
            normalizedField.setDescription(normalizeText(fieldDefinition.getDescription()));
            normalizedField.setDefaultValue(normalizeValue(fieldDefinition.getValueType(),
                    fieldDefinition.getDefaultValue(), DEFAULT_VALUE_SCOPE, fieldKey));
            normalizedSchema.add(normalizedField);
        }
        return normalizedSchema;
    }

    public Map<String, Object> normalizeGroupConfig(List<GroupConfigFieldDefinition> schema,
                                                    Map<String, Object> config,
                                                    String groupId) {
        if (schema == null || schema.isEmpty()) {
            return config == null ? Collections.emptyMap() : new LinkedHashMap<>(config);
        }
        Map<String, Object> sourceConfig = config == null ? Collections.emptyMap() : config;
        Set<String> allowedKeys = schema.stream()
                .map(GroupConfigFieldDefinition::getKey)
                .collect(java.util.stream.Collectors.toSet());
        for (String configKey : sourceConfig.keySet()) {
            if (!allowedKeys.contains(configKey)) {
                throw validationError("实验组[" + groupId + "]存在未定义配置字段: " + configKey);
            }
        }

        Map<String, Object> normalizedConfig = new LinkedHashMap<>();
        for (GroupConfigFieldDefinition fieldDefinition : schema) {
            Object rawValue = sourceConfig.containsKey(fieldDefinition.getKey())
                    ? sourceConfig.get(fieldDefinition.getKey())
                    : fieldDefinition.getDefaultValue();
            if (rawValue == null) {
                if (Boolean.TRUE.equals(fieldDefinition.getRequired())) {
                    throw validationError("实验组[" + groupId + "]缺少必填配置字段: " + fieldDefinition.getKey());
                }
                continue;
            }
            normalizedConfig.put(fieldDefinition.getKey(),
                    normalizeValue(fieldDefinition.getValueType(), rawValue, groupId, fieldDefinition.getKey()));
        }
        return normalizedConfig;
    }

    private Object normalizeValue(GroupConfigFieldDefinition.ValueType valueType,
                                  Object value,
                                  String scope,
                                  String fieldKey) {
        if (value == null) {
            return null;
        }
        return switch (valueType) {
            case STRING -> normalizeString(value, scope, fieldKey);
            case INTEGER -> normalizeInteger(value, scope, fieldKey);
            case BOOLEAN -> normalizeBoolean(value, scope, fieldKey);
            case OBJECT -> normalizeObject(value, scope, fieldKey);
            case JSON -> normalizeJson(value, scope, fieldKey);
        };
    }

    private String normalizeString(Object value, String scope, String fieldKey) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.STRING);
    }

    private Integer normalizeInteger(Object value, String scope, String fieldKey) {
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Number numberValue) {
            double doubleValue = numberValue.doubleValue();
            if (Math.floor(doubleValue) == doubleValue) {
                return numberValue.intValue();
            }
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException exception) {
                throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.INTEGER);
            }
        }
        throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.INTEGER);
    }

    private Boolean normalizeBoolean(Object value, String scope, String fieldKey) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            String normalizedValue = stringValue.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalizedValue)) {
                return true;
            }
            if ("false".equals(normalizedValue)) {
                return false;
            }
        }
        throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.BOOLEAN);
    }

    private Map<String, Object> normalizeObject(Object value, String scope, String fieldKey) {
        Object normalizedValue = value;
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            normalizedValue = parseJson(stringValue, scope, fieldKey);
        }
        if (normalizedValue instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalizedObject = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                normalizedObject.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalizedObject;
        }
        throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.OBJECT);
    }

    private Object normalizeJson(Object value, String scope, String fieldKey) {
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return parseJson(stringValue, scope, fieldKey);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        throw valueTypeError(scope, fieldKey, GroupConfigFieldDefinition.ValueType.JSON);
    }

    private Object parseJson(String json, String scope, String fieldKey) {
        try {
            return jsonUtil.toObject(json, Object.class);
        } catch (IllegalStateException exception) {
            throw validationError(scope + "字段[" + fieldKey + "]不是合法 JSON");
        }
    }

    private BusinessException valueTypeError(String scope, String fieldKey,
                                             GroupConfigFieldDefinition.ValueType valueType) {
        return validationError(scope + "字段[" + fieldKey + "]" + VALUE_TYPE_SUFFIX + "，期望类型: " + valueType.name());
    }

    private BusinessException validationError(String message) {
        return new BusinessException(ResponseCode.VALIDATION_ERROR, message);
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
