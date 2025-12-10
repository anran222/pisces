package com.pisces.common.response;

import lombok.Data;
import java.io.Serializable;

/**
 * 用户响应DTO
 */
@Data
public class UserResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id;
    
    private String username;
    
    private String password; // 通常不返回，但保留字段用于内部使用
    
    private String email;
    
    private String nickname;
    
    /**
     * 角色：ADMIN-管理员, CREATOR-创建者, VIEWER-查看者
     */
    private String role;
    
    /**
     * 状态：ACTIVE-激活, INACTIVE-未激活, LOCKED-锁定
     */
    private String status;
    
    private String createTime;
}

