package com.pisces.service.service;

import com.pisces.common.enums.Permission;
import com.pisces.common.request.UserCreateRequest;
import com.pisces.common.request.UserQueryRequest;
import com.pisces.common.response.UserResponse;
import com.pisces.service.model.entity.UserEntity;

import java.util.List;

/**
 * 用户服务接口
 */
public interface UserService {
    
    /**
     * 创建用户
     */
    UserResponse createUser(UserCreateRequest request);
    
    /**
     * 根据ID查询用户
     */
    UserResponse getUserById(Long id);
    
    /**
     * 根据用户名查询用户
     */
    UserEntity getUserByUsername(String username);
    
    /**
     * 查询用户列表
     */
    List<UserResponse> queryUsers(UserQueryRequest request);
    
    /**
     * 更新用户
     */
    UserResponse updateUser(Long id, UserCreateRequest request);
    
    /**
     * 删除用户
     */
    void deleteUser(Long id);
    
    /**
     * 检查用户权限
     */
    boolean hasPermission(String username, Permission permission);
    
    /**
     * 检查用户是否为管理员
     */
    boolean isAdmin(String username);
}

