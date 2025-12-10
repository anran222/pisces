package com.pisces.service.repository;

import com.pisces.service.model.entity.UserEntity;

import java.util.List;

/**
 * 用户Repository接口
 */
public interface UserRepository {
    
    /**
     * 保存用户
     */
    UserEntity save(UserEntity user);
    
    /**
     * 根据ID查询用户
     */
    UserEntity findById(Long id);
    
    /**
     * 根据用户名查询用户
     */
    UserEntity findByUsername(String username);
    
    /**
     * 根据邮箱查询用户
     */
    UserEntity findByEmail(String email);
    
    /**
     * 查询用户列表
     */
    List<UserEntity> findList(String username, String email, String role, 
                       String status, Integer pageNum, Integer pageSize);
    
    /**
     * 统计用户数量
     */
    long count(String username, String email, String role, String status);
    
    /**
     * 更新用户
     */
    void update(UserEntity user);
    
    /**
     * 删除用户
     */
    void deleteById(Long id);
}

