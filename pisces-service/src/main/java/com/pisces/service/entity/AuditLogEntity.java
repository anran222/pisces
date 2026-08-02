package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Data
public class AuditLogEntity {

    private Long id;

    private String auditId;

    private String resourceType;

    private String resourceId;

    private String action;

    private String operator;

    private String beforeStatus;

    private String afterStatus;

    private String summary;

    private String detailJson;

    private LocalDateTime createdAt;
}
