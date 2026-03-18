package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentReportSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验报告快照Mapper
 */
@Mapper
public interface ExperimentReportSnapshotMapper {

    /**
     * 插入实验报告快照
     *
     * @param entity 实验报告快照实体
     * @return 影响行数
     */
    int insert(@Param("entity") ExperimentReportSnapshotEntity entity);

    /**
     * 查询实验报告快照列表
     *
     * @param experimentId 实验ID
     * @return 快照实体列表
     */
    List<ExperimentReportSnapshotEntity> selectByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 查询下一个快照版本
     *
     * @param experimentId 实验ID
     * @return 下一个快照版本
     */
    Integer selectNextVersion(@Param("experimentId") String experimentId);
}
