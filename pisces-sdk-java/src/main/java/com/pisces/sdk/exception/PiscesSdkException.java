package com.pisces.sdk.exception;

/**
 * SDK统一异常
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 18:09
 */
public class PiscesSdkException extends RuntimeException {

    private final String code;
    private final Integer httpStatus;
    private final String requestPath;
    private final String responseBody;

    public PiscesSdkException(String message) {
        this(message, null, null, null, null, null);
    }

    public PiscesSdkException(String message, Throwable cause) {
        this(message, null, null, null, null, cause);
    }

    public PiscesSdkException(String message, String code, Integer httpStatus, String requestPath, String responseBody) {
        this(message, code, httpStatus, requestPath, responseBody, null);
    }

    public PiscesSdkException(String message, String code, Integer httpStatus, String requestPath,
                              String responseBody, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.requestPath = requestPath;
        this.responseBody = responseBody;
    }

    public String getCode() {
        return code;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
