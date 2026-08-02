package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentConfigDraftEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 实验配置草稿Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 09:09
 */
@Mapper
public interface ExperimentConfigDraftMapper {

    /**
     * 保存或更新配置草稿
     *
     * @param entity 配置草稿实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentConfigDraftEntity entity);

    /**
     * 查询配置草稿
     *
     * @param experimentId 实验ID
     * @return 配置草稿实体
     */
    ExperimentConfigDraftEntity selectByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 删除配置草稿
     *
     * @param experimentId 实验ID
     * @return 影响行数
     */
    int deleteByExperimentId(@Param("experimentId") String experimentId);
}
