package com.pisces.service.ai;

import com.pisces.common.model.GroupConfigFieldDefinition;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * AI实验设计两阶段规划上下文
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/23 14:42
 */
@Getter
public class AIDesignPlanningContext {

    private final List<String> schemaKeyHints;
    private final Map<String, Object> baselineConfig;
    private final List<GroupConfigFieldDefinition> existingSchema;
    private final List<String> draftGroupIds;
    private final String trafficStrategy;
    private final List<String> prioritizedConstraints;
    private final List<String> disabledSchemaKeys;
    private final boolean baselineProvided;

    public AIDesignPlanningContext(List<String> schemaKeyHints,
                                   Map<String, Object> baselineConfig,
                                   List<GroupConfigFieldDefinition> existingSchema,
                                   List<String> draftGroupIds,
                                   String trafficStrategy,
                                   List<String> prioritizedConstraints,
                                   List<String> disabledSchemaKeys,
                                   boolean baselineProvided) {
        this.schemaKeyHints = schemaKeyHints == null ? List.of() : List.copyOf(schemaKeyHints);
        this.baselineConfig = baselineConfig == null ? Map.of() : Map.copyOf(baselineConfig);
        this.existingSchema = existingSchema == null ? List.of() : List.copyOf(existingSchema);
        this.draftGroupIds = draftGroupIds == null ? List.of() : List.copyOf(draftGroupIds);
        this.trafficStrategy = trafficStrategy;
        this.prioritizedConstraints = prioritizedConstraints == null ? List.of() : List.copyOf(prioritizedConstraints);
        this.disabledSchemaKeys = disabledSchemaKeys == null ? List.of() : List.copyOf(disabledSchemaKeys);
        this.baselineProvided = baselineProvided;
    }
}
