package com.pisces.common.response;

import lombok.Data;

/**
 * 分流结果响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:48
 */
@Data
public class TrafficAssignmentResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 原始访客ID
     */
    private String visitorId;

    /**
     * 归一化访客ID
     */
    private String canonicalVisitorId;

    /**
     * 实验组ID
     */
    private String groupId;

    /**
     * 是否成功进入实验
     */
    private Boolean assigned;

    /**
     * 分流结果原因
     */
    private String reason;

    /**
     * 结果来源：CACHE / NEW_ASSIGNMENT / BLOCKED
     */
    private String source;

    /**
     * 分流策略
     */
    private String strategy;

    /**
     * 配置版本
     */
    private Long configVersion;
}
