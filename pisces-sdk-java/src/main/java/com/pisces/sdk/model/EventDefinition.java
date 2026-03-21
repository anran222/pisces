package com.pisces.sdk.model;

/**
 * 实验事件定义
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/21 18:42
 */
public class EventDefinition {

    private String key;

    private String label;

    private String description;

    private String category;

    private Boolean primary;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getPrimary() {
        return primary;
    }

    public void setPrimary(Boolean primary) {
        this.primary = primary;
    }
}
