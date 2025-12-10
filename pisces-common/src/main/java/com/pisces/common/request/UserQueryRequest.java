package com.pisces.common.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryRequest extends BaseRequest {
    
    private String username;
    
    private String email;
    
    private Integer pageNum = 1;
    
    private Integer pageSize = 10;
}

