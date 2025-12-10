package com.pisces.common.response;

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
    
    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(200);
        response.setMessage("操作成功");
        response.setData(data);
        return response;
    }
    
    public static <T> BaseResponse<T> success(String message, T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(200);
        response.setMessage(message);
        response.setData(data);
        return response;
    }
    
    public static <T> BaseResponse<T> error(String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(500);
        response.setMessage(message);
        return response;
    }
    
    public static <T> BaseResponse<T> error(Integer code, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}

