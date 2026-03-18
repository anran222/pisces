package com.pisces.service.mapper;

import com.pisces.service.entity.ExperimentConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实验配置Mapper
 */
@Mapper
public interface ExperimentConfigMapper {

    /**
     * 保存或更新实验配置
     *
     * @param entity 实验配置实体
     * @return 影响行数
     */
    int upsert(@Param("entity") ExperimentConfigEntity entity);

    /**
     * 按实验ID查询实验配置
     *
     * @param experimentId 实验ID
     * @return 实验配置实体
     */
    ExperimentConfigEntity selectByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 删除实验配置
     *
     * @param experimentId 实验ID
     * @return 影响行数
     */
    int deleteByExperimentId(@Param("experimentId") String experimentId);

    /**
     * 查询全部实验ID
     *
     * @return 实验ID列表
     */
    List<String> selectAllExperimentIds();
}
