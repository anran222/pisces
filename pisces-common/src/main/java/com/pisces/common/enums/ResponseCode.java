package com.pisces.common.enums;

import lombok.Getter;

/**
 * 响应码枚举
 */
@Getter
public enum ResponseCode {
    
    // 成功
    SUCCESS(200, "操作成功"),
    
    // 客户端错误 4xx
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权，请先登录"),
    FORBIDDEN(403, "没有权限执行此操作"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    CONFLICT(409, "资源冲突"),
    
    // 服务器错误 5xx
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),
    
    // 业务错误码 1000+
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "用户已存在"),
    USER_PASSWORD_ERROR(1003, "用户名或密码错误"),
    USER_STATUS_ERROR(1004, "用户状态异常"),
    USER_PERMISSION_DENIED(1005, "用户权限不足"),
    
    TOKEN_INVALID(2001, "Token无效"),
    TOKEN_EXPIRED(2002, "Token已过期"),
    TOKEN_MISSING(2003, "Token缺失"),
    TOKEN_BLACKLISTED(2004, "Token已失效"),
    
    EXPERIMENT_NOT_FOUND(3001, "实验不存在"),
    EXPERIMENT_ALREADY_EXISTS(3002, "实验已存在"),
    EXPERIMENT_STATUS_ERROR(3003, "实验状态异常"),
    EXPERIMENT_PERMISSION_DENIED(3004, "没有权限操作此实验"),
    
    VALIDATION_ERROR(4001, "参数校验失败"),
    DATA_NOT_FOUND(4002, "数据不存在"),
    OPERATION_FAILED(4003, "操作失败");
    
    /**
     * 响应码
     */
    private final Integer code;
    
    /**
     * 响应消息
     */
    private final String message;
    
    ResponseCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
    
    /**
     * 根据code获取枚举
     */
    public static ResponseCode getByCode(Integer code) {
        for (ResponseCode responseCode : values()) {
            if (responseCode.getCode().equals(code)) {
                return responseCode;
            }
        }
        return INTERNAL_SERVER_ERROR;
    }
}

