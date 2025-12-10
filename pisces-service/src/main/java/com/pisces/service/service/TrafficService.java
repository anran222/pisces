package com.pisces.service.service;

import java.util.Map;

/**
 * 流量分配服务接口
 */
public interface TrafficService {
    
    /**
     * 分配用户到实验组
     */
    String assignGroup(String experimentId, String userId);
    
    /**
     * 获取用户所在组
     */
    String getUserGroup(String experimentId, String userId);
    
    /**
     * 获取用户参与的所有实验
     */
    Map<String, String> getUserExperiments(String userId);
}

