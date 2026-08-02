package com.pisces.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * 实验配置模型
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class ExperimentConfig {

    private String id;
    private String name;
    private String description;
    private String status;
    private Long configVersion;
    private List<EventDefinition> eventDefinitions;
    private List<MetricDefinition> metricDefinitions;
    private List<GroupConfigFieldDefinition> groupConfigSchema;
    private Map<String, ExperimentGroupConfig> groups;
    private TrafficConfig traffic;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }

    public List<EventDefinition> getEventDefinitions() {
        return eventDefinitions;
    }

    public void setEventDefinitions(List<EventDefinition> eventDefinitions) {
        this.eventDefinitions = eventDefinitions;
    }

    public List<MetricDefinition> getMetricDefinitions() {
        return metricDefinitions;
    }

    public void setMetricDefinitions(List<MetricDefinition> metricDefinitions) {
        this.metricDefinitions = metricDefinitions;
    }

    public List<GroupConfigFieldDefinition> getGroupConfigSchema() {
        return groupConfigSchema;
    }

    public void setGroupConfigSchema(List<GroupConfigFieldDefinition> groupConfigSchema) {
        this.groupConfigSchema = groupConfigSchema;
    }

    public Map<String, ExperimentGroupConfig> getGroups() {
        return groups;
    }

    public void setGroups(Map<String, ExperimentGroupConfig> groups) {
        this.groups = groups;
    }

    public TrafficConfig getTraffic() {
        return traffic;
    }

    public void setTraffic(TrafficConfig traffic) {
        this.traffic = traffic;
    }

    public static class TrafficConfig {
        private Double totalTraffic;
        private List<GroupAllocation> allocation;
        private String strategy;
        private String hashKey;

        public Double getTotalTraffic() {
            return totalTraffic;
        }

        public void setTotalTraffic(Double totalTraffic) {
            this.totalTraffic = totalTraffic;
        }

        public List<GroupAllocation> getAllocation() {
            return allocation;
        }

        public void setAllocation(List<GroupAllocation> allocation) {
            this.allocation = allocation;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getHashKey() {
            return hashKey;
        }

        public void setHashKey(String hashKey) {
            this.hashKey = hashKey;
        }
    }

    public static class GroupAllocation {
        private String group;
        private Double ratio;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public Double getRatio() {
            return ratio;
        }

        public void setRatio(Double ratio) {
            this.ratio = ratio;
        }
    }
}
