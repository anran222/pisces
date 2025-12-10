package com.pisces.common.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token信息
 */
@Data
public class TokenInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * Token值
     */
    private String token;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 过期时间
     */
    private LocalDateTime expireTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

