package com.pisces.service.exception;

import com.pisces.common.enums.ResponseCode;
import lombok.Getter;

/**
 * 业务异常类
 */
@Getter
public class BusinessException extends RuntimeException {
    
    private final ResponseCode responseCode;
    
    /**
     * 使用响应码枚举构造异常
     */
    public BusinessException(ResponseCode responseCode) {
        super(responseCode.getMessage());
        this.responseCode = responseCode;
    }
    
    /**
     * 使用响应码枚举和自定义消息构造异常
     */
    public BusinessException(ResponseCode responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }
    
    /**
     * 使用响应码枚举和异常原因构造异常
     */
    public BusinessException(ResponseCode responseCode, String message, Throwable cause) {
        super(message, cause);
        this.responseCode = responseCode;
    }
    
    /**
     * 获取响应码
     */
    public Integer getCode() {
        return responseCode.getCode();
    }
}

