package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.util.Set;

/**
 * 流量分层（Layer）配置
 *
 * <p>分层的核心作用：在同一层内的实验互斥（一个访客同时只能进入该层的一个实验），
 * 不同层之间的实验正交（同一访客可以同时参加多个层的实验）。</p>
 *
 * <p>典型场景：
 * <ul>
 *   <li>MUTEX：同一页面的UI改版实验，避免多组叠加干扰</li>
 *   <li>ORTHOGONAL：UI实验 × 算法实验，不同维度可以正交运行</li>
 * </ul>
 * </p>
 */
@Data
public class ExperimentLayer implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 层ID（唯一标识）
     */
    private String layerId;

    /**
     * 层名称
     */
    private String layerName;

    /**
     * 层描述
     */
    private String description;

    /**
     * 分层策略
     */
    private LayerStrategy strategy;

    /**
     * 该层分配的总流量比例（0.0~1.0）
     * 同一层内所有实验共享这部分流量
     */
    private double trafficShare = 1.0;

    /**
     * 该层包含的实验ID集合
     */
    private Set<String> experimentIds;

    /**
     * 分层策略枚举
     */
    public enum LayerStrategy {
        /**
         * 互斥：同层内一个访客只能进入一个实验
         */
        MUTEX,

        /**
         * 正交：不同层之间独立，同一访客可同时参加多个层的实验
         * 当只有一层时，ORTHOGONAL 与 MUTEX 效果相同
         */
        ORTHOGONAL
    }
}
