package com.pisces.api.traffic;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 流量分配控制器（无用户系统版本）
 * 使用visitorId替代userId，无需Token认证
 */
@RestController
@RequestMapping("/traffic")
@NoTokenRequired  // 无需Token认证
public class TrafficController {
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired
    private MultiArmedBanditService mabService;
    
    /**
     * 分配访客到实验组（无用户系统版本）
     * 使用visitorId（访客唯一标识，可以是设备ID、会话ID等）
     */
    @PostMapping("/assign")
    public BaseResponse<String> assignGroup(@RequestBody Map<String, String> request) {
        String experimentId = request.get("experimentId");
        String visitorId = request.get("visitorId");
        
        if (experimentId == null || visitorId == null) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, "experimentId和visitorId不能为空");
        }
        
        String groupId = trafficService.assignGroup(experimentId, visitorId);
        return BaseResponse.of(groupId);
    }
    
    
    /**
     * 获取多臂老虎机算法的Beta分布参数（Thompson Sampling）
     * AI赋能：获取变体的Beta分布参数，用于监控和调试
     */
    @GetMapping("/experiment/{experimentId}/mab/beta")
    public BaseResponse<Map<String, Integer>> getBetaParameters(
            @PathVariable String experimentId,
            @RequestParam String groupId) {
        Map<String, Integer> params = mabService.getBetaParameters(experimentId, groupId);
        return BaseResponse.of(params);
    }
    
    /**
     * 获取多臂老虎机算法的统计信息（UCB）
     * AI赋能：获取变体的UCB统计信息，包括平均奖励、选择次数等
     */
    @GetMapping("/experiment/{experimentId}/mab/stats")
    public BaseResponse<Map<String, Object>> getGroupStatistics(
            @PathVariable String experimentId,
            @RequestParam String groupId) {
        Map<String, Object> stats = mabService.getGroupStatistics(experimentId, groupId);
        return BaseResponse.of(stats);
    }
}

