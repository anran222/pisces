package com.pisces.service.exception;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.response.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数校验失败: {}", errors);
        return BaseResponse.error(ResponseCode.VALIDATION_ERROR, "参数校验失败: " + errors);
    }
    
    /**
     * 处理业务异常
     * 直接使用异常中的ResponseCode的code和message
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> handleBusinessException(BusinessException ex) {
        ResponseCode responseCode = ex.getResponseCode();
        HttpStatus httpStatus = getHttpStatus(responseCode);
        
        // 直接使用异常中的ResponseCode的code和message
        // 如果异常有自定义消息，使用自定义消息；否则使用ResponseCode的默认消息
        String message = ex.getMessage();
        
        log.warn("业务异常: code={}, message={}", responseCode.getCode(), message);
        BaseResponse<Void> response = BaseResponse.error(responseCode, message);
        return ResponseEntity.status(httpStatus).body(response);
    }
    
    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public BaseResponse<Void> handleException(Exception ex) {
        log.error("系统异常", ex);
        return BaseResponse.error(ResponseCode.INTERNAL_SERVER_ERROR, "系统异常: " + ex.getMessage());
    }
    
    /**
     * 根据响应码获取HTTP状态码
     */
    private HttpStatus getHttpStatus(ResponseCode responseCode) {
        int code = responseCode.getCode();
        if (code >= 400 && code < 500) {
            if (code == 401) {
                return HttpStatus.UNAUTHORIZED;
            } else if (code == 403) {
                return HttpStatus.FORBIDDEN;
            } else if (code == 404) {
                return HttpStatus.NOT_FOUND;
            } else {
                return HttpStatus.BAD_REQUEST;
            }
        } else {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}

