package com.pisces.service.mapper;

import com.pisces.service.entity.ApplicationEventDefinitionEntity;
import com.pisces.service.entity.ApplicationMetricDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用字典Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/16 19:02
 */
@Mapper
public interface ApplicationDictionaryMapper {

    int upsertEventDefinition(@Param("entity") ApplicationEventDefinitionEntity entity);

    int upsertMetricDefinition(@Param("entity") ApplicationMetricDefinitionEntity entity);

    List<ApplicationEventDefinitionEntity> selectEventDefinitionsByAppId(@Param("appId") String appId);

    List<ApplicationMetricDefinitionEntity> selectMetricDefinitionsByAppId(@Param("appId") String appId);
}
