package com.pisces.service.mapper;

import com.pisces.service.entity.ApplicationSpaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用空间Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 12:10
 */
@Mapper
public interface ApplicationSpaceMapper {

    int insert(@Param("entity") ApplicationSpaceEntity entity);

    int upsert(@Param("entity") ApplicationSpaceEntity entity);

    ApplicationSpaceEntity selectByAppId(@Param("appId") String appId);

    List<ApplicationSpaceEntity> selectAll();
}
