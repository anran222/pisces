package com.pisces.common.request;

import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.response.ExperimentResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验配置 schema 协议测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/21 16:48
 */
class ExperimentCreateRequestSchemaTest {

    @Test
    void shouldExposeGroupConfigSchemaAcrossRequestMetadataAndResponse() {
        GroupConfigFieldDefinition stringField = buildField("mainTitle", "主标题",
                GroupConfigFieldDefinition.ValueType.STRING, true, "默认标题");
        GroupConfigFieldDefinition integerField = buildField("badgeCount", "角标数量",
                GroupConfigFieldDefinition.ValueType.INTEGER, false, 2);
        GroupConfigFieldDefinition booleanField = buildField("showQualityBadge", "展示质检标签",
                GroupConfigFieldDefinition.ValueType.BOOLEAN, false, true);
        GroupConfigFieldDefinition objectField = buildField("ctaConfig", "按钮配置",
                GroupConfigFieldDefinition.ValueType.OBJECT, false, null);
        GroupConfigFieldDefinition jsonField = buildField("highlightTags", "标签列表",
                GroupConfigFieldDefinition.ValueType.JSON, false, List.of("官方质检"));
        List<GroupConfigFieldDefinition> schema = List.of(
                stringField, integerField, booleanField, objectField, jsonField);

        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setGroupConfigSchema(schema);

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setGroupConfigSchema(schema);

        ExperimentResponse response = new ExperimentResponse();
        response.setGroupConfigSchema(schema);

        assertThat(request.getGroupConfigSchema()).hasSize(5);
        assertThat(metadata.getGroupConfigSchema()).extracting(GroupConfigFieldDefinition::getKey)
                .containsExactly("mainTitle", "badgeCount", "showQualityBadge", "ctaConfig", "highlightTags");
        assertThat(response.getGroupConfigSchema()).extracting(GroupConfigFieldDefinition::getValueType)
                .containsExactly(
                        GroupConfigFieldDefinition.ValueType.STRING,
                        GroupConfigFieldDefinition.ValueType.INTEGER,
                        GroupConfigFieldDefinition.ValueType.BOOLEAN,
                        GroupConfigFieldDefinition.ValueType.OBJECT,
                        GroupConfigFieldDefinition.ValueType.JSON
                );
    }

    private GroupConfigFieldDefinition buildField(String key, String label,
                                                  GroupConfigFieldDefinition.ValueType valueType,
                                                  boolean required, Object defaultValue) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        fieldDefinition.setDefaultValue(defaultValue);
        return fieldDefinition;
    }
}
