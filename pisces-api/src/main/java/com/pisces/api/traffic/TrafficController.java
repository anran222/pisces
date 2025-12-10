package com.pisces.api.traffic;

import com.pisces.common.response.BaseResponse;
import com.pisces.common.response.UserGroupResponse;
import com.pisces.service.service.TrafficService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 流量分配控制器
 */
@RestController
@RequestMapping("/traffic")
public class TrafficController {
    
    @Autowired
    private TrafficService trafficService;
    
    /**
     * 分配用户到实验组
     */
    @PostMapping("/assign")
    public BaseResponse<String> assignGroup(@RequestParam String experimentId, 
                                            @RequestParam String userId) {
        String groupId = trafficService.assignGroup(experimentId, userId);
        return BaseResponse.of(groupId);
    }
    
    /**
     * 获取用户所在组
     */
    @GetMapping("/user/{userId}/group")
    public BaseResponse<String> getUserGroup(@PathVariable String userId, 
                                            @RequestParam String experimentId) {
        String groupId = trafficService.getUserGroup(experimentId, userId);
        return BaseResponse.of(groupId);
    }
    
    /**
     * 获取用户参与的所有实验
     */
    @GetMapping("/user/{userId}/experiments")
    public BaseResponse<UserGroupResponse> getUserExperiments(@PathVariable String userId) {
        Map<String, String> experiments = trafficService.getUserExperiments(userId);
        
        UserGroupResponse response = new UserGroupResponse();
        response.setUserId(userId);
        response.setExperiments(experiments);
        
        return BaseResponse.of(response);
    }
}

