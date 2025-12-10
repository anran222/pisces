package com.pisces.common.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

/**
 * 事件上报请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EventReportRequest extends BaseRequest {
    
    @NotBlank(message = "实验ID不能为空")
    private String experimentId;
    
    @NotBlank(message = "用户ID不能为空")
    private String userId;
    
    @NotBlank(message = "事件类型不能为空")
    private String eventType; // VIEW, CLICK, CONVERT
    
    @NotBlank(message = "事件名称不能为空")
    private String eventName;
    
    /**
     * 事件属性
     */
    private Map<String, Object> properties;
}

