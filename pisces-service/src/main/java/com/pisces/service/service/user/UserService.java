package com.pisces.service.service.user;

import com.pisces.common.request.UserCreateRequest;
import com.pisces.common.request.UserQueryRequest;
import com.pisces.common.response.UserResponse;

import java.util.List;

/**
 * 用户服务接口
 */
public interface UserService {
    
    /**
     * 创建用户
     * @param request 用户创建请求
     * @return 用户响应
     */
    UserResponse createUser(UserCreateRequest request);
    
    /**
     * 根据ID查询用户
     * @param id 用户ID
     * @return 用户响应
     */
    UserResponse getUserById(Long id);
    
    /**
     * 查询用户列表
     * @param request 查询请求
     * @return 用户列表
     */
    List<UserResponse> queryUsers(UserQueryRequest request);
    
    /**
     * 删除用户
     * @param id 用户ID
     */
    void deleteUser(Long id);
}

