package com.pisces.service.repository.impl;

import com.pisces.service.model.entity.UserEntity;
import com.pisces.service.mapper.UserMapper;
import com.pisces.service.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户Repository实现类
 */
@Slf4j
@Repository
public class UserRepositoryImpl implements UserRepository {
    
    @Autowired
    private UserMapper userMapper;
    
    @Override
    public UserEntity save(UserEntity user) {
        if (user.getId() == null) {
            // 新增
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());
            userMapper.insert(user);
            log.debug("新增用户: {}", user.getUsername());
        } else {
            // 更新
            user.setUpdateTime(LocalDateTime.now());
            userMapper.update(user);
            log.debug("更新用户: {}", user.getId());
        }
        return user;
    }
    
    @Override
    public UserEntity findById(Long id) {
        return userMapper.selectById(id);
    }
    
    @Override
    public UserEntity findByUsername(String username) {
        return userMapper.selectByUsername(username);
    }
    
    @Override
    public UserEntity findByEmail(String email) {
        return userMapper.selectByEmail(email);
    }
    
    @Override
    public List<UserEntity> findList(String username, String email, String role, 
                              String status, Integer pageNum, Integer pageSize) {
        Integer offset = (pageNum - 1) * pageSize;
        return userMapper.selectList(username, email, role, status, offset, pageSize);
    }
    
    @Override
    public long count(String username, String email, String role, String status) {
        return userMapper.count(username, email, role, status);
    }
    
    @Override
    public void update(UserEntity user) {
        user.setUpdateTime(LocalDateTime.now());
        userMapper.update(user);
    }
    
    @Override
    public void deleteById(Long id) {
        userMapper.deleteById(id);
    }
}

