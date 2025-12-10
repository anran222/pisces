package com.pisces.common.response;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

/**
 * 用户分组响应
 */
@Data
public class UserGroupResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户参与的实验及所在组（实验ID -> 组ID）
     */
    private Map<String, String> experiments;
}

