package com.pisces.service.service.impl;

import com.pisces.service.service.IdentityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 访客身份管理服务实现（基于 Redis）
 *
 * <p>Redis Key 设计：
 * <ul>
 *   <li>{@code pisces:identity:bind:{deviceId}} → userId（deviceId→userId 正向映射）</li>
 *   <li>{@code pisces:identity:device:{userId}} → deviceId（userId→deviceId 反向索引，可选）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class IdentityServiceImpl implements IdentityService {

    private static final String BIND_PREFIX   = "pisces:identity:bind:";   // deviceId → userId
    private static final long   BIND_EXPIRE_DAYS = 365;                    // 绑定关系保留 1 年

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public String getOrCreateVisitorId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        // 若已有绑定，返回 userId；否则返回 deviceId 本身
        return resolveCanonicalId(deviceId);
    }

    @Override
    public void bindUserId(String deviceId, String userId) {
        if (deviceId == null || deviceId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("deviceId 和 userId 均不能为空");
        }
        String key = BIND_PREFIX + deviceId;
        redisTemplate.opsForValue().set(key, userId, BIND_EXPIRE_DAYS, TimeUnit.DAYS);
        log.info("身份绑定: deviceId={} → userId={}", deviceId, userId);
    }

    @Override
    public String resolveCanonicalId(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            return visitorId;
        }
        Object mapped = redisTemplate.opsForValue().get(BIND_PREFIX + visitorId);
        return mapped != null ? mapped.toString() : visitorId;
    }

    @Override
    public void unbind(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        redisTemplate.delete(BIND_PREFIX + deviceId);
        log.info("解除身份绑定: deviceId={}", deviceId);
    }
}
