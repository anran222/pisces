package com.pisces.api.data;

import com.pisces.common.request.EventReportRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.service.DataService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 数据上报控制器
 */
@RestController
@RequestMapping("/data")
public class DataController {
    
    @Autowired
    private DataService dataService;
    
    /**
     * 上报事件
     */
    @PostMapping("/event")
    public BaseResponse<Void> reportEvent(@Valid @RequestBody EventReportRequest request) {
        dataService.reportEvent(
                request.getExperimentId(),
                request.getUserId(),
                request.getEventType(),
                request.getEventName(),
                request.getProperties()
        );
        return BaseResponse.of("事件上报成功", null);
    }
}

