package com.pisces.service.config;

import java.util.function.Consumer;

/**
 * 实验配置跨实例变更广播器。
 */
public interface ExperimentConfigChangeBroadcaster {

    /**
     * 广播实验配置变更。
     *
     * @param experimentId 实验ID
     */
    void publishExperimentChange(String experimentId);

    /**
     * 注册远端实验配置变更监听器。
     *
     * @param listener 变更监听器
     */
    void addExperimentChangeListener(Consumer<String> listener);
}
