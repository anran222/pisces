package com.pisces.service.model.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类
 */
@Data
public class UserEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long id;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 密码（加密后）
     */
    private String password;
    
    /**
     * 邮箱
     */
    private String email;
    
    /**
     * 昵称
     */
    private String nickname;
    
    /**
     * 角色：ADMIN-管理员, CREATOR-创建者, VIEWER-查看者
     */
    private UserRole role;
    
    /**
     * 状态：ACTIVE-激活, INACTIVE-未激活, LOCKED-锁定
     */
    private UserStatus status;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
    
    /**
     * 用户角色枚举
     */
    public enum UserRole {
        ADMIN,      // 管理员
        CREATOR,    // 创建者
        VIEWER      // 查看者
    }
    
    /**
     * 用户状态枚举
     */
    public enum UserStatus {
        ACTIVE,     // 激活
        INACTIVE,   // 未激活
        LOCKED      // 锁定
    }
}

