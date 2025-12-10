package com.pisces.service.service.impl;

import com.pisces.common.request.UserCreateRequest;
import com.pisces.common.request.UserQueryRequest;
import com.pisces.common.response.UserResponse;
import com.pisces.service.service.user.UserService;
import com.pisces.service.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    
    private final ConcurrentHashMap<Long, UserResponse> userStore = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public UserResponse createUser(UserCreateRequest request) {
        log.info("创建用户，用户名: {}", request.getUsername());
        
        UserResponse user = new UserResponse();
        user.setId(idGenerator.getAndIncrement());
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());
        user.setCreateTime(LocalDateTime.now().format(formatter));
        
        userStore.put(user.getId(), user);
        
        log.info("用户创建成功，ID: {}", user.getId());
        return user;
    }
    
    @Override
    public UserResponse getUserById(Long id) {
        log.info("查询用户，ID: {}", id);
        UserResponse user = userStore.get(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }
    
    @Override
    public List<UserResponse> queryUsers(UserQueryRequest request) {
        log.info("查询用户列表，用户名: {}, 邮箱: {}", request.getUsername(), request.getEmail());
        
        List<UserResponse> allUsers = new ArrayList<>(userStore.values());
        
        // 根据条件过滤
        return allUsers.stream()
                .filter(user -> {
                    if (request.getUsername() != null && !request.getUsername().isEmpty()) {
                        return user.getUsername().contains(request.getUsername());
                    }
                    return true;
                })
                .filter(user -> {
                    if (request.getEmail() != null && !request.getEmail().isEmpty()) {
                        return user.getEmail() != null && user.getEmail().contains(request.getEmail());
                    }
                    return true;
                })
                .skip((long) (request.getPageNum() - 1) * request.getPageSize())
                .limit(request.getPageSize())
                .collect(Collectors.toList());
    }
    
    @Override
    public void deleteUser(Long id) {
        log.info("删除用户，ID: {}", id);
        UserResponse user = userStore.get(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userStore.remove(id);
        log.info("用户删除成功，ID: {}", id);
    }
}

