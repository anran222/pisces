package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行时配置版本响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:53
 */
@Data
public class RuntimeExperimentConfigVersionResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 客户端已知版本
     */
    private Long knownVersion;

    /**
     * 服务端当前版本
     */
    private Long currentVersion;

    /**
     * 配置是否已变化
     */
    private Boolean changed;

    /**
     * 实验状态
     */
    private String status;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;
}
