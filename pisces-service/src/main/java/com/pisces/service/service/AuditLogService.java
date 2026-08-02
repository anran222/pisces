package com.pisces.service.service;

import com.pisces.common.response.AuditLogResponse;
import com.pisces.service.audit.AuditLogRecord;

import java.util.List;

/**
 * 审计日志服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
public interface AuditLogService {

    void record(AuditLogRecord record);

    List<AuditLogResponse> listExperimentAuditLogs(String experimentId);
}
