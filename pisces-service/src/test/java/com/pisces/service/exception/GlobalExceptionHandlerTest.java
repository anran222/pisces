package com.pisces.service.exception;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessExceptionShouldMapConflictToHttp409() {
        ResponseEntity<BaseResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ResponseCode.CONFLICT, "当前实验已有事件重放任务正在运行"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResponseCode.CONFLICT.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("当前实验已有事件重放任务正在运行");
    }
}
