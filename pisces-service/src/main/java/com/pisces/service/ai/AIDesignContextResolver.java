package com.pisces.service.ai;

import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.request.AIDesignRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI实验设计上下文归一化器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/23 13:58
 */
@Component
public class AIDesignContextResolver {

    private static final String DEFAULT_TRAFFIC_STRATEGY = TrafficConfig.TrafficStrategy.HASH.name();
    private static final String DEFAULT_CONTROL_GROUP_ID = "control";
    private static final String DEFAULT_VARIANT_GROUP_PREFIX = "variant_";
    private static final int MIN_GROUP_SIZE = 2;

    public AIDesignPlanningContext resolve(AIDesignRequest request) {
        AIDesignRequest.DesignContext designContext = request == null ? null : request.getDesignContext();
        AIDesignRequest.DesignPreferences designPreferences = request == null ? null : request.getDesignPreferences();
        List<String> disabledSchemaKeys = normalizeDistinctTextList(
                designPreferences == null ? null : designPreferences.getDisabledSchemaKeys());
        Map<String, Object> baselineConfig = normalizeBaselineConfig(request == null ? null : request.getBaselineConfig(),
                disabledSchemaKeys);
        List<GroupConfigFieldDefinition> existingSchema = normalizeExistingSchema(
                request == null ? null : request.getExistingSchema(), disabledSchemaKeys);
        List<String> draftGroupIds = resolveDraftGroupIds(designContext, designPreferences);
        return new AIDesignPlanningContext(
                resolveSchemaKeyHints(designContext, disabledSchemaKeys),
                baselineConfig,
                existingSchema,
                draftGroupIds,
                resolveTrafficStrategy(designContext, designPreferences),
                normalizeDistinctTextList(designContext == null ? null : designContext.getPrioritizedConstraints()),
                disabledSchemaKeys,
                !baselineConfig.isEmpty());
    }

    private List<String> resolveSchemaKeyHints(AIDesignRequest.DesignContext designContext,
                                               List<String> disabledSchemaKeys) {
        List<String> schemaKeyHints = normalizeDistinctTextList(designContext == null ? null : designContext.getSchemaKeys());
        if (schemaKeyHints.isEmpty() || disabledSchemaKeys.isEmpty()) {
            return schemaKeyHints;
        }
        List<String> filteredSchemaKeyHints = new ArrayList<>();
        for (String schemaKeyHint : schemaKeyHints) {
            if (!disabledSchemaKeys.contains(schemaKeyHint)) {
                filteredSchemaKeyHints.add(schemaKeyHint);
            }
        }
        return filteredSchemaKeyHints;
    }

    private Map<String, Object> normalizeBaselineConfig(Map<String, Object> baselineConfig,
                                                        List<String> disabledSchemaKeys) {
        if (baselineConfig == null || baselineConfig.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalizedBaselineConfig = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : baselineConfig.entrySet()) {
            String normalizedKey = normalizeText(entry.getKey());
            if (!StringUtils.hasText(normalizedKey) || disabledSchemaKeys.contains(normalizedKey)) {
                continue;
            }
            normalizedBaselineConfig.put(normalizedKey, entry.getValue());
        }
        return normalizedBaselineConfig;
    }

    private List<GroupConfigFieldDefinition> normalizeExistingSchema(List<GroupConfigFieldDefinition> existingSchema,
                                                                     List<String> disabledSchemaKeys) {
        if (existingSchema == null || existingSchema.isEmpty()) {
            return List.of();
        }
        Set<String> schemaKeys = new HashSet<>();
        List<GroupConfigFieldDefinition> normalizedSchema = new ArrayList<>();
        for (GroupConfigFieldDefinition fieldDefinition : existingSchema) {
            if (fieldDefinition == null) {
                continue;
            }
            String normalizedKey = normalizeText(fieldDefinition.getKey());
            if (!StringUtils.hasText(normalizedKey)
                    || disabledSchemaKeys.contains(normalizedKey)
                    || !schemaKeys.add(normalizedKey)) {
                continue;
            }
            GroupConfigFieldDefinition normalizedField = new GroupConfigFieldDefinition();
            normalizedField.setKey(normalizedKey);
            normalizedField.setLabel(defaultValue(fieldDefinition.getLabel(), normalizedKey));
            normalizedField.setValueType(fieldDefinition.getValueType());
            normalizedField.setRequired(Boolean.TRUE.equals(fieldDefinition.getRequired()));
            normalizedField.setDescription(normalizeText(fieldDefinition.getDescription()));
            normalizedField.setDefaultValue(fieldDefinition.getDefaultValue());
            normalizedSchema.add(normalizedField);
        }
        return normalizedSchema;
    }

    private List<String> resolveDraftGroupIds(AIDesignRequest.DesignContext designContext,
                                              AIDesignRequest.DesignPreferences designPreferences) {
        List<String> draftGroupIds = normalizeDistinctTextList(designContext == null ? null : designContext.getDraftGroupIds());
        if (!draftGroupIds.isEmpty()) {
            return draftGroupIds;
        }
        if (designPreferences == null || designPreferences.getExpectedGroupCount() == null) {
            return List.of();
        }
        return buildDefaultGroupIds(Math.max(designPreferences.getExpectedGroupCount(), MIN_GROUP_SIZE));
    }

    private List<String> buildDefaultGroupIds(int groupCount) {
        List<String> groupIds = new ArrayList<>();
        groupIds.add(DEFAULT_CONTROL_GROUP_ID);
        for (int i = 1; i < groupCount; i++) {
            groupIds.add(DEFAULT_VARIANT_GROUP_PREFIX + suffix(i));
        }
        return groupIds;
    }

    private String suffix(int index) {
        if (index <= 0) {
            return "a";
        }
        StringBuilder suffix = new StringBuilder();
        int value = index;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            suffix.insert(0, (char) ('a' + remainder));
            value = (value - 1) / 26;
        }
        return suffix.toString();
    }

    private String resolveTrafficStrategy(AIDesignRequest.DesignContext designContext,
                                          AIDesignRequest.DesignPreferences designPreferences) {
        String candidateStrategy = designPreferences == null ? null : designPreferences.getPreferredTrafficStrategy();
        if (!StringUtils.hasText(candidateStrategy) && designContext != null) {
            candidateStrategy = designContext.getTrafficStrategy();
        }
        if (!StringUtils.hasText(candidateStrategy)) {
            return DEFAULT_TRAFFIC_STRATEGY;
        }
        TrafficConfig.TrafficStrategy trafficStrategy = TrafficConfig.TrafficStrategy.of(candidateStrategy);
        return trafficStrategy == null ? DEFAULT_TRAFFIC_STRATEGY : trafficStrategy.name();
    }

    private List<String> normalizeDistinctTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalizedValues = new ArrayList<>();
        for (String value : values) {
            String normalizedValue = normalizeText(value);
            if (!StringUtils.hasText(normalizedValue) || normalizedValues.contains(normalizedValue)) {
                continue;
            }
            normalizedValues.add(normalizedValue);
        }
        return normalizedValues;
    }

    private String defaultValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
