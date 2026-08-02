package com.pisces.service.audit;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计日志记录
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Data
public class AuditLogRecord {

    private Long id;

    private String auditId;

    private String resourceType;

    private String resourceId;

    private String action;

    private String operator;

    private String beforeStatus;

    private String afterStatus;

    private String summary;

    private Map<String, Object> detail;

    private LocalDateTime createdAt;
}
