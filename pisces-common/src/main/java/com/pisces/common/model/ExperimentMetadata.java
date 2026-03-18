package com.pisces.common.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;

/**
 * 实验元数据
 */
@Data
public class ExperimentMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 配置版本号（每次更新实验配置时自增）
     * TrafficService 用此字段感知配置变更，使旧缓存失效
     */
    private long configVersion = 1L;

    /**
     * 所属流量分层 ID（支持实验互斥/正交分层，null 表示不属于任何层）
     */
    private String layerId;

    /**
     * 实验基本信息
     */
    private Experiment experiment;
    
    /**
     * 实验组列表
     */
    private Map<String, ExperimentGroup> groups;
    
    /**
     * 流量配置
     */
    private TrafficConfig traffic;
    
    /**
     * 白名单用户ID列表
     */
    private List<String> whitelist;
    
    /**
     * 黑名单用户ID列表
     */
    private List<String> blacklist;

    /**
     * 指标定义列表
     */
    private List<MetricDefinition> metricDefinitions;

    /**
     * 当前结论状态
     */
    private ConclusionStatus conclusionStatus;

    /**
     * 结论状态更新时间
     */
    private LocalDateTime conclusionUpdatedAt;

    /**
     * 系统建议结论状态（运行时派生，不作为配置持久化）
     */
    private transient ConclusionStatus suggestedConclusionStatus;

    /**
     * 系统建议结论状态更新时间（运行时派生，不作为配置持久化）
     */
    private transient LocalDateTime suggestedConclusionUpdatedAt;

    /**
     * 结论状态 (Conclusion status)
     */
    public enum ConclusionStatus {
        NOT_READY,
        RUNNING,
        READY_FOR_REVIEW,
        GRADUATED,
        REJECTED;

        public static ConclusionStatus of(String code) {
            if (code == null) {
                return null;
            }
            String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(item -> item.name().equals(normalizedCode))
                    .findFirst()
                    .orElse(null);
        }

        public static ConclusionStatus ofOrThrow(String code) {
            ConclusionStatus conclusionStatus = of(code);
            if (conclusionStatus == null) {
                throw new IllegalArgumentException("不支持的结论状态: " + code);
            }
            return conclusionStatus;
        }

        public boolean isTerminal() {
            return this == GRADUATED || this == REJECTED;
        }

        public boolean canTransitionTo(ConclusionStatus targetStatus) {
            if (targetStatus == null) {
                return false;
            }
            if (this == targetStatus) {
                return true;
            }
            return allowedTransitions().contains(targetStatus);
        }

        public Set<ConclusionStatus> allowedTransitions() {
            return switch (this) {
                case NOT_READY -> EnumSet.of(RUNNING);
                case RUNNING -> EnumSet.of(READY_FOR_REVIEW);
                case READY_FOR_REVIEW -> EnumSet.of(GRADUATED, REJECTED);
                case GRADUATED, REJECTED -> EnumSet.noneOf(ConclusionStatus.class);
            };
        }
    }
}
