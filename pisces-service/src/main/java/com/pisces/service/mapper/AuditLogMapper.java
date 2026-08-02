package com.pisces.service.mapper;

import com.pisces.service.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计日志Mapper
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
@Mapper
public interface AuditLogMapper {

    int insert(@Param("entity") AuditLogEntity entity);

    List<AuditLogEntity> selectByResource(@Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId,
                                          @Param("limit") int limit);
}
