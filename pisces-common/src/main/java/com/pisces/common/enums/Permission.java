package com.pisces.common.enums;

import lombok.Getter;

/**
 * 权限枚举
 */
@Getter
public enum Permission {
    
    /**
     * 创建实验
     */
    EXPERIMENT_CREATE("EXPERIMENT_CREATE", "创建实验"),
    
    /**
     * 更新实验
     */
    EXPERIMENT_UPDATE("EXPERIMENT_UPDATE", "更新实验"),
    
    /**
     * 删除实验
     */
    EXPERIMENT_DELETE("EXPERIMENT_DELETE", "删除实验"),
    
    /**
     * 查看实验
     */
    EXPERIMENT_VIEW("EXPERIMENT_VIEW", "查看实验"),
    
    /**
     * 查看分析
     */
    ANALYSIS_VIEW("ANALYSIS_VIEW", "查看分析"),
    
    /**
     * 用户管理
     */
    USER_MANAGE("USER_MANAGE", "用户管理");
    
    /**
     * 权限代码
     */
    private final String code;
    
    /**
     * 权限描述
     */
    private final String description;
    
    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    /**
     * 根据code获取枚举
     */
    public static Permission getByCode(String code) {
        for (Permission permission : values()) {
            if (permission.getCode().equals(code)) {
                return permission;
            }
        }
        return null;
    }
}

