package com.pisces.api.data;

import com.pisces.common.request.EventReportRequest;
import com.pisces.common.request.ExposureReportRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.DataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 数据上报控制器（无用户系统版本）
 * 使用visitorId替代userId，无需Token认证
 */
@RestController
@RequestMapping("/data")
@ApiKeyScopeRequired(ApiKeyScope.RUNTIME)
@NoTokenRequired  // 无需Token认证
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;
    
    /**
     * 上报事件
     */
    /**
     * 上报事件（无用户系统版本，使用visitorId）
     */
    @PostMapping("/event")
    public BaseResponse<Void> reportEvent(@Valid @RequestBody EventReportRequest request) {
        dataService.reportEvent(
                request.getExperimentId(),
                request.getVisitorId(),  // 使用visitorId替代userId
                request.getEventType(),
                request.getEventName(),
                request.getProperties()
        );
        return BaseResponse.of("事件上报成功", null);
    }

    /**
     * 上报实验曝光
     */
    @PostMapping("/exposure")
    public BaseResponse<Void> reportExposure(@Valid @RequestBody ExposureReportRequest request) {
        dataService.reportExposure(
                request.getExperimentId(),
                request.getVisitorId(),
                request.getProperties()
        );
        return BaseResponse.of("曝光上报成功", null);
    }
}
