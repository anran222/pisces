package com.pisces.service.service.impl;

import com.pisces.common.enums.Permission;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.request.UserCreateRequest;
import com.pisces.common.request.UserQueryRequest;
import com.pisces.common.response.UserResponse;
import com.pisces.service.service.UserService;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.model.entity.UserEntity;
import com.pisces.service.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户服务实现
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * 创建用户
     */
    @Override
    public UserResponse createUser(UserCreateRequest request) {
        // 检查用户名是否已存在
        UserEntity existingUser = userRepository.findByUsername(request.getUsername());
        if (existingUser != null) {
            throw new BusinessException(ResponseCode.USER_ALREADY_EXISTS);
        }
        
        // 检查邮箱是否已存在
        if (request.getEmail() != null) {
            UserEntity existingEmail = userRepository.findByEmail(request.getEmail());
            if (existingEmail != null) {
                throw new BusinessException(ResponseCode.CONFLICT, "邮箱已被使用");
            }
        }
        
        // 创建用户实体
        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername());
        user.setPassword(encryptPassword(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());
        user.setRole(UserEntity.UserRole.VIEWER); // 默认角色为查看者
        user.setStatus(UserEntity.UserStatus.ACTIVE); // 默认状态为激活
        
        // 保存用户
        user = userRepository.save(user);
        
        log.info("创建用户成功: {}", user.getUsername());
        return convertToResponse(user);
    }
    
    /**
     * 根据ID查询用户
     */
    @Override
    public UserResponse getUserById(Long id) {
        UserEntity user = userRepository.findById(id);
        if (user == null) {
            throw new BusinessException(ResponseCode.USER_NOT_FOUND);
        }
        return convertToResponse(user);
    }
    
    /**
     * 根据用户名查询用户
     */
    @Override
    public UserEntity getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }
    
    /**
     * 查询用户列表
     */
    @Override
    public List<UserResponse> queryUsers(UserQueryRequest request) {
        List<UserEntity> users = userRepository.findList(
                request.getUsername(),
                request.getEmail(),
                null,
                null,
                request.getPageNum(),
                request.getPageSize()
        );
        
        return users.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * 更新用户
     */
    @Override
    public UserResponse updateUser(Long id, UserCreateRequest request) {
        UserEntity user = userRepository.findById(id);
        if (user == null) {
            throw new BusinessException(ResponseCode.USER_NOT_FOUND);
        }
        
        // 检查用户名是否被其他用户使用
        UserEntity existingUser = userRepository.findByUsername(request.getUsername());
        if (existingUser != null && !existingUser.getId().equals(id)) {
            throw new BusinessException(ResponseCode.USER_ALREADY_EXISTS);
        }
        
        // 检查邮箱是否被其他用户使用
        if (request.getEmail() != null) {
            UserEntity existingEmail = userRepository.findByEmail(request.getEmail());
            if (existingEmail != null && !existingEmail.getId().equals(id)) {
                throw new BusinessException(ResponseCode.CONFLICT, "邮箱已被使用");
            }
        }
        
        // 更新用户信息
        user.setUsername(request.getUsername());
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(encryptPassword(request.getPassword()));
        }
        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());
        
        userRepository.update(user);
        
        log.info("更新用户成功: {}", id);
        return convertToResponse(user);
    }
    
    /**
     * 删除用户
     */
    @Override
    public void deleteUser(Long id) {
        UserEntity user = userRepository.findById(id);
        if (user == null) {
            throw new BusinessException(ResponseCode.USER_NOT_FOUND);
        }
        
        userRepository.deleteById(id);
        log.info("删除用户成功: {}", id);
    }
    
    /**
     * 检查用户权限
     */
    @Override
    public boolean hasPermission(String username, Permission permission) {
        if (permission == null) {
            return false;
        }
        
        UserEntity user = userRepository.findByUsername(username);
        if (user == null) {
            return false;
        }
        
        // 检查用户状态
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            return false;
        }
        
        // 根据角色判断权限
        UserEntity.UserRole role = user.getRole();
        switch (permission) {
            case EXPERIMENT_CREATE:
            case EXPERIMENT_UPDATE:
            case EXPERIMENT_DELETE:
                return role == UserEntity.UserRole.ADMIN || role == UserEntity.UserRole.CREATOR;
            case EXPERIMENT_VIEW:
            case ANALYSIS_VIEW:
                return true; // 所有角色都可以查看
            case USER_MANAGE:
                return role == UserEntity.UserRole.ADMIN;
            default:
                return false;
        }
    }
    
    /**
     * 检查用户是否为管理员
     */
    @Override
    public boolean isAdmin(String username) {
        UserEntity user = userRepository.findByUsername(username);
        return user != null && user.getRole() == UserEntity.UserRole.ADMIN;
    }
    
    /**
     * 加密密码
     */
    private String encryptPassword(String password) {
        // 使用MD5加密（实际项目中应使用BCrypt等更安全的加密方式）
        return DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 转换为响应对象
     */
    private UserResponse convertToResponse(UserEntity user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setNickname(user.getNickname());
        response.setRole(user.getRole() != null ? user.getRole().name() : null);
        response.setStatus(user.getStatus() != null ? user.getStatus().name() : null);
        response.setCreateTime(user.getCreateTime() != null ? 
                user.getCreateTime().toString() : null);
        // 不返回密码
        response.setPassword(null);
        return response;
    }
}

