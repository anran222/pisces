package com.pisces.common.response;

import com.pisces.common.enums.ResponseCode;
import lombok.Data;
import java.io.Serializable;

/**
 * 基础响应类
 */
@Data
public class BaseResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 响应码
     */
    private Integer code;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 响应数据
     */
    private T data;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    public BaseResponse() {
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 创建成功响应（使用默认成功消息）
     */
    public static <T> BaseResponse<T> of(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setMessage(ResponseCode.SUCCESS.getMessage());
        response.setData(data);
        return response;
    }
    
    /**
     * 创建成功响应（自定义消息）
     */
    public static <T> BaseResponse<T> of(String message, T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setMessage(message);
        response.setData(data);
        return response;
    }
    
    /**
     * 创建成功响应（使用响应码枚举）
     */
    public static <T> BaseResponse<T> of(ResponseCode responseCode, T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(responseCode.getCode());
        response.setMessage(responseCode.getMessage());
        response.setData(data);
        return response;
    }
    
    /**
     * 创建错误响应（使用响应码枚举）
     */
    public static <T> BaseResponse<T> error(ResponseCode responseCode) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(responseCode.getCode());
        response.setMessage(responseCode.getMessage());
        return response;
    }
    
    /**
     * 创建错误响应（使用响应码枚举，自定义消息）
     */
    public static <T> BaseResponse<T> error(ResponseCode responseCode, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(responseCode.getCode());
        response.setMessage(message);
        return response;
    }
    
    // 保留旧方法以保持向后兼容
    @Deprecated
    public static <T> BaseResponse<T> success(T data) {
        return of(data);
    }
    
    @Deprecated
    public static <T> BaseResponse<T> success(String message, T data) {
        return of(message, data);
    }
    
    @Deprecated
    public static <T> BaseResponse<T> error(String message) {
        return error(ResponseCode.INTERNAL_SERVER_ERROR, message);
    }
    
    @Deprecated
    public static <T> BaseResponse<T> error(Integer code, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}

