package com.pisces.service.mapper;

import com.pisces.service.model.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户Mapper接口
 */
@Mapper
public interface UserMapper {
    
    /**
     * 插入用户
     */
    int insert(UserEntity user);
    
    /**
     * 根据ID查询用户
     */
    UserEntity selectById(@Param("id") Long id);
    
    /**
     * 根据用户名查询用户
     */
    UserEntity selectByUsername(@Param("username") String username);
    
    /**
     * 根据邮箱查询用户
     */
    UserEntity selectByEmail(@Param("email") String email);
    
    /**
     * 查询用户列表
     */
    List<UserEntity> selectList(@Param("username") String username, 
                         @Param("email") String email,
                         @Param("role") String role,
                         @Param("status") String status,
                         @Param("offset") Integer offset,
                         @Param("limit") Integer limit);
    
    /**
     * 统计用户数量
     */
    long count(@Param("username") String username,
              @Param("email") String email,
              @Param("role") String role,
              @Param("status") String status);
    
    /**
     * 更新用户
     */
    int update(UserEntity user);
    
    /**
     * 删除用户
     */
    int deleteById(@Param("id") Long id);
}

