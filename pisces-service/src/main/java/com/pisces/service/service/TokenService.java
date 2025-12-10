package com.pisces.service.service;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.TokenInfo;
import com.pisces.service.model.entity.UserEntity;
import com.pisces.service.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Token服务（使用Redis存储）
 */
@Slf4j
@Service
public class TokenService {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Token过期时间（小时）
     */
    @Value("${token.expire-hours:24}")
    private int expireHours;
    
    /**
     * Token刷新阈值（小时），在过期前多少小时可以刷新
     */
    @Value("${token.refresh-threshold-hours:2}")
    private int refreshThresholdHours;
    
    /**
     * Token存储的key前缀
     */
    @Value("${token.redis.key-prefix:pisces:token:}")
    private String tokenKeyPrefix;
    
    /**
     * Token黑名单的key前缀
     */
    @Value("${token.redis.blacklist-prefix:pisces:token:blacklist:}")
    private String blacklistKeyPrefix;
    
    /**
     * 黑名单过期时间（天）
     */
    @Value("${token.redis.blacklist-expire-days:7}")
    private int blacklistExpireDays;
    
    /**
     * 生成Token
     * 如果用户已有有效的Token，则复用；否则生成新的Token
     */
    public String generateToken(String username) {
        // 检查用户是否存在
        UserEntity user = userService.getUserByUsername(username);
        if (user == null) {
            throw new BusinessException(ResponseCode.USER_NOT_FOUND);
        }
        
        // 检查用户状态
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            throw new BusinessException(ResponseCode.USER_STATUS_ERROR, "用户状态异常，无法登录");
        }
        
        // 先从Redis查找该用户是否已有有效的Token
        TokenInfo existingTokenInfo = findValidTokenByUsername(username);
        if (existingTokenInfo != null) {
            String existingToken = existingTokenInfo.getToken();
            // 检查Token是否在黑名单中
            String blacklistKey = blacklistKeyPrefix + existingToken;
            if (!redisTemplate.hasKey(blacklistKey)) {
                // Token不在黑名单中，且已通过过期检查，可以复用
                log.info("复用已有Token: 用户={}, Token={}", username, existingToken.substring(0, 10) + "...");
                return existingToken;
            }
            // Token在黑名单中，需要生成新Token
            log.debug("已有Token在黑名单中，生成新Token: 用户={}", username);
        }
        
        // 生成新Token
        String token = generateTokenValue(username);
        
        // 创建Token信息
        TokenInfo tokenInfo = new TokenInfo();
        tokenInfo.setToken(token);
        tokenInfo.setUsername(username);
        tokenInfo.setUserId(user.getId());
        tokenInfo.setCreateTime(LocalDateTime.now());
        tokenInfo.setExpireTime(LocalDateTime.now().plusHours(expireHours));
        
        // 如果用户已有其他Token，将旧Token加入黑名单（实现单点登录）
        if (existingTokenInfo != null) {
            String oldToken = existingTokenInfo.getToken();
            String blacklistKey = blacklistKeyPrefix + oldToken;
            redisTemplate.opsForValue().set(blacklistKey, true, blacklistExpireDays, TimeUnit.DAYS);
            String oldTokenKey = tokenKeyPrefix + oldToken;
            redisTemplate.delete(oldTokenKey);
            log.debug("将旧Token加入黑名单: 用户={}", username);
        }
        
        // 存储新Token到Redis，设置过期时间
        String tokenKey = tokenKeyPrefix + token;
        redisTemplate.opsForValue().set(tokenKey, tokenInfo, expireHours, TimeUnit.HOURS);
        
        log.info("生成Token成功: 用户={}, Token={}", username, token.substring(0, 10) + "...");
        return token;
    }
    
    /**
     * 根据用户名查找有效的Token
     * 注意：此方法使用KEYS命令，在生产环境中如果Token数量很大，建议使用SCAN命令或维护用户名到Token的映射
     */
    private TokenInfo findValidTokenByUsername(String username) {
        String pattern = tokenKeyPrefix + "*";
        Set<String> keys = redisTemplate.keys(pattern);

        for (String key : keys) {
            try {
                TokenInfo tokenInfo = (TokenInfo) redisTemplate.opsForValue().get(key);
                if (tokenInfo != null && username.equals(tokenInfo.getUsername())) {
                    // 检查Token是否过期
                    if (tokenInfo.getExpireTime() != null &&
                        LocalDateTime.now().isBefore(tokenInfo.getExpireTime())) {
                        return tokenInfo;
                    }
                }
            } catch (Exception e) {
                log.debug("查找Token失败: key={}", key, e);
            }
        }

        return null;
    }
    
    /**
     * 验证Token
     */
    public TokenInfo validateToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new BusinessException(ResponseCode.TOKEN_MISSING, "Token不能为空，请检查请求头是否正确设置");
        }
        
        // 检查Token是否在黑名单中
        String blacklistKey = blacklistKeyPrefix + token;
        if (redisTemplate.hasKey(blacklistKey)) {
            log.warn("Token在黑名单中: Token={}", token.length() > 10 ? token.substring(0, 10) + "..." : token);
            throw new BusinessException(ResponseCode.TOKEN_BLACKLISTED);
        }
        
        // 从Redis获取Token信息
        String tokenKey = tokenKeyPrefix + token;
        TokenInfo tokenInfo = (TokenInfo) redisTemplate.opsForValue().get(tokenKey);
        
        if (tokenInfo == null) {
            log.warn("Token不存在: Token={}", token.length() > 10 ? token.substring(0, 10) + "..." : token);
            throw new BusinessException(ResponseCode.TOKEN_INVALID, "Token不存在或已失效，请重新登录");
        }
        
        // 检查Token是否过期（Redis会自动处理过期，但这里做双重检查）
        if (tokenInfo.getExpireTime() != null && 
            LocalDateTime.now().isAfter(tokenInfo.getExpireTime())) {
            redisTemplate.delete(tokenKey);
            log.warn("Token已过期: 用户={}, 过期时间={}", tokenInfo.getUsername(), tokenInfo.getExpireTime());
            throw new BusinessException(ResponseCode.TOKEN_EXPIRED);
        }
        
        // 检查用户状态
        UserEntity user = userService.getUserByUsername(tokenInfo.getUsername());
        if (user == null) {
            redisTemplate.delete(tokenKey);
            log.warn("用户不存在: 用户名={}", tokenInfo.getUsername());
            throw new BusinessException(ResponseCode.USER_NOT_FOUND, "用户不存在，Token已失效");
        }
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            redisTemplate.delete(tokenKey);
            log.warn("用户状态异常: 用户名={}, 状态={}", tokenInfo.getUsername(), user.getStatus());
            throw new BusinessException(ResponseCode.USER_STATUS_ERROR, 
                "用户状态异常（" + user.getStatus() + "），Token已失效。请联系管理员");
        }
        
        // 自动续期：如果Token即将过期（在刷新阈值内），则延长过期时间
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime refreshThreshold = now.plusHours(refreshThresholdHours);
        if (tokenInfo.getExpireTime() != null && 
            tokenInfo.getExpireTime().isBefore(refreshThreshold)) {
            tokenInfo.setExpireTime(now.plusHours(expireHours));
            // 更新Redis中的过期时间
            redisTemplate.opsForValue().set(tokenKey, tokenInfo, expireHours, TimeUnit.HOURS);
            log.debug("Token自动续期: 用户={}", tokenInfo.getUsername());
        }
        
        return tokenInfo;
    }
    
    /**
     * 刷新Token
     */
    public TokenInfo refreshToken(String token) {
        // 先验证原Token
        TokenInfo oldTokenInfo = validateToken(token);
        
        // 生成新Token
        String newToken = generateTokenValue(oldTokenInfo.getUsername());
        
        // 创建新Token信息
        TokenInfo newTokenInfo = new TokenInfo();
        newTokenInfo.setToken(newToken);
        newTokenInfo.setUsername(oldTokenInfo.getUsername());
        newTokenInfo.setUserId(oldTokenInfo.getUserId());
        newTokenInfo.setCreateTime(LocalDateTime.now());
        newTokenInfo.setExpireTime(LocalDateTime.now().plusHours(expireHours));
        
        // 将旧Token加入黑名单
        String blacklistKey = blacklistKeyPrefix + token;
        redisTemplate.opsForValue().set(blacklistKey, true, blacklistExpireDays, TimeUnit.DAYS);
        
        // 存储新Token
        String newTokenKey = tokenKeyPrefix + newToken;
        redisTemplate.opsForValue().set(newTokenKey, newTokenInfo, expireHours, TimeUnit.HOURS);
        
        // 删除旧Token
        String oldTokenKey = tokenKeyPrefix + token;
        redisTemplate.delete(oldTokenKey);
        
        log.info("Token刷新成功: 用户={}", oldTokenInfo.getUsername());
        return newTokenInfo;
    }
    
    /**
     * 删除Token（登出）
     */
    public void removeToken(String token) {
        if (token != null && !token.isEmpty()) {
            // 将Token加入黑名单
            String blacklistKey = blacklistKeyPrefix + token;
            redisTemplate.opsForValue().set(blacklistKey, true, blacklistExpireDays, TimeUnit.DAYS);
            
            // 从存储中删除
            String tokenKey = tokenKeyPrefix + token;
            redisTemplate.delete(tokenKey);
            
            log.debug("删除Token: {}", token);
        }
    }
    
    /**
     * 获取Token信息（不验证，仅查询）
     */
    public TokenInfo getTokenInfo(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String tokenKey = tokenKeyPrefix + token;
        return (TokenInfo) redisTemplate.opsForValue().get(tokenKey);
    }
    
    /**
     * 生成Token值
     */
    private String generateTokenValue(String username) {
        String rawToken = username + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID();
        return DigestUtils.md5DigestAsHex(rawToken.getBytes(StandardCharsets.UTF_8));
    }
}
