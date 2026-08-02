package com.pisces.service.repository;

import com.pisces.service.audit.AuditLogRecord;

import java.util.List;

/**
 * 审计日志仓库
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
public interface AuditLogRepository {

    void save(AuditLogRecord record);

    List<AuditLogRecord> listByResource(String resourceType, String resourceId, int limit);
}
