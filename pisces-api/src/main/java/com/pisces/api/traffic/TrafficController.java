package com.pisces.api.traffic;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.request.TrafficAssignRequest;
import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.TrafficAssignmentResponse;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.MultiArmedBanditService;
import com.pisces.service.service.TrafficService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 流量分配控制器（无用户系统版本）
 * 使用visitorId替代userId，无需Token认证
 */
@RestController
@RequestMapping("/traffic")
@ApiKeyScopeRequired(ApiKeyScope.RUNTIME)
@NoTokenRequired  // 无需Token认证
@RequiredArgsConstructor
public class TrafficController {

    private final TrafficService trafficService;

    private final MultiArmedBanditService mabService;
    
    /**
     * 分配访客到实验组（无用户系统版本）
     * 使用visitorId（访客唯一标识，可以是设备ID、会话ID等）
     */
    @PostMapping("/assign")
    public BaseResponse<String> assignGroup(@RequestBody TrafficAssignRequest request) {
        String experimentId = request.getExperimentId();
        String visitorId = request.getVisitorId();
        
        if (experimentId == null || visitorId == null) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, "experimentId和visitorId不能为空");
        }
        
        String groupId = trafficService.assignGroup(experimentId, visitorId, request.getAttributes());
        return BaseResponse.of(groupId);
    }

    /**
     * 分配访客到实验组，并返回命中原因与配置版本
     */
    @PostMapping("/assign/trace")
    public BaseResponse<TrafficAssignmentResponse> assignGroupWithTrace(@RequestBody TrafficAssignRequest request) {
        String experimentId = request.getExperimentId();
        String visitorId = request.getVisitorId();

        if (experimentId == null || visitorId == null) {
            return BaseResponse.error(ResponseCode.BAD_REQUEST, "experimentId和visitorId不能为空");
        }

        return BaseResponse.of(trafficService.assignGroupWithTrace(experimentId, visitorId, request.getAttributes()));
    }
    
    
    /**
     * 获取多臂老虎机算法的Beta分布参数（Thompson Sampling）
     * AI赋能：获取变体的Beta分布参数，用于监控和调试
     */
    @GetMapping("/experiment/{experimentId}/mab/beta")
    @ApiKeyScopeRequired(ApiKeyScope.ANALYSIS)
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
    @ApiKeyScopeRequired(ApiKeyScope.ANALYSIS)
    public BaseResponse<Map<String, Object>> getGroupStatistics(
            @PathVariable String experimentId,
            @RequestParam String groupId) {
        Map<String, Object> stats = mabService.getGroupStatistics(experimentId, groupId);
        return BaseResponse.of(stats);
    }
    
    /**
     * 获取多臂老虎机算法的流量分配概率
     * AI赋能：实时计算各变体被选中的概率，用于监控流量分配情况
     */
    @GetMapping("/experiment/{experimentId}/mab/probabilities")
    @ApiKeyScopeRequired(ApiKeyScope.ANALYSIS)
    public BaseResponse<Map<String, Double>> getAllocationProbabilities(
            @PathVariable String experimentId) {
        Map<String, Double> probabilities = mabService.getAllocationProbabilities(experimentId);
        return BaseResponse.of(probabilities);
    }
    
    /**
     * 获取多臂老虎机算法的综合统计摘要
     * AI赋能：获取实验的MAB算法综合信息，包括收敛状态、领先组、推荐操作等
     */
    @GetMapping("/experiment/{experimentId}/mab/summary")
    @ApiKeyScopeRequired(ApiKeyScope.ANALYSIS)
    public BaseResponse<Map<String, Object>> getMABSummary(
            @PathVariable String experimentId) {
        Map<String, Object> summary = mabService.getMABSummary(experimentId);
        return BaseResponse.of(summary);
    }
    
    /**
     * 重置实验的MAB统计数据
     * 注意：此操作会清除实验的所有MAB学习数据，请谨慎使用
     */
    @PostMapping("/experiment/{experimentId}/mab/reset")
    @ApiKeyScopeRequired(ApiKeyScope.MANAGEMENT)
    public BaseResponse<Void> resetMABData(@PathVariable String experimentId) {
        mabService.resetMABData(experimentId);
        return BaseResponse.of("MAB数据重置成功", null);
    }
}
