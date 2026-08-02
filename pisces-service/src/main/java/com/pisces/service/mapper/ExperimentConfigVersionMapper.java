package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentConfigVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验配置版本Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:20
 */
@Mapper
public interface ExperimentConfigVersionMapper {

    /**
     * 保存或更新配置版本
     *
     * @param entity 配置版本实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentConfigVersionEntity entity);

    /**
     * 按实验ID和版本查询配置版本
     *
     * @param experimentId 实验ID
     * @param configVersion 配置版本
     * @return 配置版本实体
     */
    ExperimentConfigVersionEntity selectByExperimentIdAndVersion(@Param("experimentId") String experimentId,
                                                                  @Param("configVersion") Long configVersion);

    /**
     * 查询实验配置版本列表
     *
     * @param experimentId 实验ID
     * @return 配置版本实体列表
     */
    List<ExperimentConfigVersionEntity> selectByExperimentId(@Param("experimentId") String experimentId);
}
