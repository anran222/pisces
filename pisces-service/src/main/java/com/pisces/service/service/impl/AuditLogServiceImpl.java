package com.pisces.service.service.impl;

import com.pisces.common.response.AuditLogResponse;
import com.pisces.service.audit.AuditLogConstants;
import com.pisces.service.audit.AuditLogRecord;
import com.pisces.service.repository.AuditLogRepository;
import com.pisces.service.service.AuditLogService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 审计日志服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Service
@AllArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final int DEFAULT_QUERY_LIMIT = 200;
    private static final int AUDIT_ID_LENGTH = 12;

    private final AuditLogRepository auditLogRepository;

    @Override
    public void record(AuditLogRecord record) {
        normalizeRecord(record);
        auditLogRepository.save(record);
    }

    @Override
    public List<AuditLogResponse> listExperimentAuditLogs(String experimentId) {
        if (!StringUtils.hasText(experimentId)) {
            return Collections.emptyList();
        }
        return auditLogRepository
                .listByResource(AuditLogConstants.RESOURCE_TYPE_EXPERIMENT, experimentId, DEFAULT_QUERY_LIMIT)
                .stream()
                .map(this::buildAuditLogResponse)
                .toList();
    }

    private void normalizeRecord(AuditLogRecord record) {
        if (!StringUtils.hasText(record.getAuditId())) {
            String uuid = UUID.randomUUID().toString().replace("-", "");
            record.setAuditId("aud_" + uuid.substring(0, AUDIT_ID_LENGTH));
        }
        if (!StringUtils.hasText(record.getOperator())) {
            record.setOperator(AuditLogConstants.OPERATOR_SYSTEM);
        }
        if (record.getDetail() == null) {
            record.setDetail(Collections.emptyMap());
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(LocalDateTime.now());
        }
    }

    private AuditLogResponse buildAuditLogResponse(AuditLogRecord record) {
        AuditLogResponse response = new AuditLogResponse();
        response.setAuditId(record.getAuditId());
        response.setResourceType(record.getResourceType());
        response.setResourceId(record.getResourceId());
        response.setAction(record.getAction());
        response.setOperator(record.getOperator());
        response.setBeforeStatus(record.getBeforeStatus());
        response.setAfterStatus(record.getAfterStatus());
        response.setSummary(record.getSummary());
        response.setDetail(record.getDetail());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }
}
