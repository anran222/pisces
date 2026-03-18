package com.pisces.service.repository;

import com.pisces.common.model.ExperimentReportSnapshot;

import java.util.List;

/**
 * 实验报告快照仓库
 */
public interface ExperimentReportSnapshotRepository {

    /**
     * 保存快照
     *
     * @param snapshot 实验报告快照
     * @return 保存后的快照
     */
    ExperimentReportSnapshot save(ExperimentReportSnapshot snapshot);

    /**
     * 查询实验快照列表
     *
     * @param experimentId 实验ID
     * @return 快照列表
     */
    List<ExperimentReportSnapshot> listByExperimentId(String experimentId);

    /**
     * 获取下一个快照版本
     *
     * @param experimentId 实验ID
     * @return 下一个快照版本
     */
    int getNextVersion(String experimentId);
}
