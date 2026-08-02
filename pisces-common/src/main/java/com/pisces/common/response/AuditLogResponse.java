package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计日志响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Data
public class AuditLogResponse {

    /**
     * 审计日志ID
     */
    private String auditId;

    /**
     * 资源类型
     */
    private String resourceType;

    /**
     * 资源ID
     */
    private String resourceId;

    /**
     * 操作类型
     */
    private String action;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 变更前状态
     */
    private String beforeStatus;

    /**
     * 变更后状态
     */
    private String afterStatus;

    /**
     * 操作摘要
     */
    private String summary;

    /**
     * 操作详情
     */
    private Map<String, Object> detail;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
