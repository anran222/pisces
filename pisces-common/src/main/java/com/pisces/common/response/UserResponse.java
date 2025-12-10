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
    
    private String email;
    
    private String nickname;
    
    private String createTime;
}

