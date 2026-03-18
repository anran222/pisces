package com.pisces.service.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.service.entity.ExperimentReportSnapshotEntity;
import com.pisces.service.mapper.ExperimentReportSnapshotMapper;
import com.pisces.service.util.JsonUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于数据库的实验报告快照仓库实现
 */
@Repository
@AllArgsConstructor
public class ExperimentReportSnapshotRepository implements
    com.pisces.service.repository.ExperimentReportSnapshotRepository {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ExperimentReportSnapshotMapper experimentReportSnapshotMapper;

    private final JsonUtil jsonUtil;

    @Override
    public ExperimentReportSnapshot save(ExperimentReportSnapshot snapshot) {
        ExperimentReportSnapshotEntity entity = buildExperimentReportSnapshotEntity(snapshot);
        experimentReportSnapshotMapper.insert(entity);
        if (entity.getId() != null) {
            snapshot.setId(entity.getId());
        }
        return snapshot;
    }

    @Override
    public List<ExperimentReportSnapshot> listByExperimentId(String experimentId) {
        return experimentReportSnapshotMapper.selectByExperimentId(experimentId).stream()
                .map(this::buildExperimentReportSnapshot)
                .toList();
    }

    @Override
    public int getNextVersion(String experimentId) {
        Integer nextVersion = experimentReportSnapshotMapper.selectNextVersion(experimentId);
        return nextVersion != null ? nextVersion : 1;
    }

    private ExperimentReportSnapshotEntity buildExperimentReportSnapshotEntity(ExperimentReportSnapshot snapshot) {
        ExperimentReportSnapshotEntity entity = new ExperimentReportSnapshotEntity();
        entity.setId(snapshot.getId());
        entity.setExperimentId(snapshot.getExperimentId());
        entity.setSnapshotVersion(snapshot.getSnapshotVersion());
        entity.setConclusionStatus(snapshot.getConclusionStatus() != null ? snapshot.getConclusionStatus().name() : null);
        entity.setPrimaryMetricKey(snapshot.getPrimaryMetricKey());
        entity.setBestPerformingGroup(snapshot.getBestPerformingGroup());
        entity.setWinningVariant(snapshot.getWinningVariant());
        entity.setAnalysisReady(snapshot.getAnalysisReady());
        entity.setHasSrm(snapshot.getHasSrm());
        entity.setBreachedGuardrailsJson(writeJson(snapshot.getBreachedGuardrails()));
        entity.setDecisionContextJson(writeJson(snapshot.getDecisionContext()));
        entity.setReportJson(writeJson(snapshot.getReport()));
        entity.setGeneratedBy(snapshot.getGeneratedBy());
        entity.setGeneratedAt(snapshot.getGeneratedAt());
        return entity;
    }

    private ExperimentReportSnapshot buildExperimentReportSnapshot(ExperimentReportSnapshotEntity entity) {
        ExperimentReportSnapshot snapshot = new ExperimentReportSnapshot();
        snapshot.setId(entity.getId());
        snapshot.setExperimentId(entity.getExperimentId());
        snapshot.setSnapshotVersion(entity.getSnapshotVersion());
        if (entity.getConclusionStatus() != null) {
            snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.valueOf(entity.getConclusionStatus()));
        }
        snapshot.setPrimaryMetricKey(entity.getPrimaryMetricKey());
        snapshot.setBestPerformingGroup(entity.getBestPerformingGroup());
        snapshot.setWinningVariant(entity.getWinningVariant());
        snapshot.setAnalysisReady(entity.getAnalysisReady());
        snapshot.setHasSrm(entity.getHasSrm());
        snapshot.setBreachedGuardrails(readStringList(entity.getBreachedGuardrailsJson()));
        snapshot.setDecisionContext(readMap(entity.getDecisionContextJson()));
        snapshot.setReport(readMap(entity.getReportJson()));
        snapshot.setGeneratedBy(entity.getGeneratedBy());
        snapshot.setGeneratedAt(entity.getGeneratedAt());
        return snapshot;
    }

    private String writeJson(Object value) {
        return jsonUtil.toJson(value);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        return jsonUtil.toObject(json, STRING_LIST_TYPE);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        return jsonUtil.toObject(json, MAP_TYPE);
    }
}
