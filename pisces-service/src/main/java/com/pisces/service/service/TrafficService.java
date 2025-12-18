package com.pisces.service.service;

import java.util.Map;

/**
 * 流量分配服务接口（无用户系统版本）
 * 使用visitorId（访客唯一标识）替代userId
 */
public interface TrafficService {
    
    /**
     * 分配访客到实验组
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识（可以是设备ID、会话ID等）
     * @return 实验组ID
     */
    String assignGroup(String experimentId, String visitorId);
    
    /**
     * 获取访客所在组
     * @param experimentId 实验ID
     * @param visitorId 访客唯一标识
     * @return 实验组ID
     */
    String getUserGroup(String experimentId, String visitorId);
    
    /**
     * 获取访客参与的所有实验
     * @param visitorId 访客唯一标识
     * @return 实验ID到组ID的映射
     */
    Map<String, String> getUserExperiments(String visitorId);
}

