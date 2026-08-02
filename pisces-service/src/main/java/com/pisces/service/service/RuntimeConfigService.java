package com.pisces.service.service;

import com.pisces.common.response.RuntimeExperimentConfigResponse;
import com.pisces.common.response.RuntimeExperimentConfigVersionResponse;

/**
 * 运行时配置服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:49
 */
public interface RuntimeConfigService {

    /**
     * 查询运行时实验配置。
     *
     * @param experimentId 实验ID
     * @return 运行时实验配置
     */
    RuntimeExperimentConfigResponse getExperimentConfig(String experimentId);

    /**
     * 查询运行时实验配置版本。
     *
     * @param experimentId 实验ID
     * @param knownVersion 客户端已知版本
     * @param waitMillis 最大等待毫秒数
     * @return 运行时实验配置版本
     */
    RuntimeExperimentConfigVersionResponse getExperimentConfigVersion(String experimentId, Long knownVersion,
                                                                      Long waitMillis);
}
