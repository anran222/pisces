package com.pisces.sdk.model;

/**
 * SDK基础响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class BaseResponse<T> {

    private Integer code;
    private String message;
    private T data;
    private Long timestamp;

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
