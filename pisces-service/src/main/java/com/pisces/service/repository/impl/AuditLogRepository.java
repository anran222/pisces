package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.service.audit.AuditLogRecord;
import com.pisces.service.entity.AuditLogEntity;
import com.pisces.service.mapper.AuditLogMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 审计日志数据访问实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Repository
@AllArgsConstructor
public class AuditLogRepository implements com.pisces.service.repository.AuditLogRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AuditLogMapper auditLogMapper;

    private final JsonUtil jsonUtil;

    @Override
    public void save(AuditLogRecord record) {
        auditLogMapper.insert(buildAuditLogEntity(record));
    }

    @Override
    public List<AuditLogRecord> listByResource(String resourceType, String resourceId, int limit) {
        return auditLogMapper.selectByResource(resourceType, resourceId, limit).stream()
                .map(this::buildAuditLogRecord)
                .toList();
    }

    private AuditLogEntity buildAuditLogEntity(AuditLogRecord record) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(record.getId());
        entity.setAuditId(record.getAuditId());
        entity.setResourceType(record.getResourceType());
        entity.setResourceId(record.getResourceId());
        entity.setAction(record.getAction());
        entity.setOperator(record.getOperator());
        entity.setBeforeStatus(record.getBeforeStatus());
        entity.setAfterStatus(record.getAfterStatus());
        entity.setSummary(record.getSummary());
        entity.setDetailJson(jsonUtil.toJson(record.getDetail()));
        entity.setCreatedAt(record.getCreatedAt());
        return entity;
    }

    private AuditLogRecord buildAuditLogRecord(AuditLogEntity entity) {
        AuditLogRecord record = new AuditLogRecord();
        record.setId(entity.getId());
        record.setAuditId(entity.getAuditId());
        record.setResourceType(entity.getResourceType());
        record.setResourceId(entity.getResourceId());
        record.setAction(entity.getAction());
        record.setOperator(entity.getOperator());
        record.setBeforeStatus(entity.getBeforeStatus());
        record.setAfterStatus(entity.getAfterStatus());
        record.setSummary(entity.getSummary());
        record.setDetail(readMap(entity.getDetailJson()));
        record.setCreatedAt(entity.getCreatedAt());
        return record;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }
}
