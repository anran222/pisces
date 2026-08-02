package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件管道状态响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:37
 */
@Data
public class EventPipelineStatusResponse {

    /**
     * 实验ID
     */
    private String experimentId;

    /**
     * 收件箱总记录数
     */
    private Long totalCount;

    /**
     * 待处理记录数
     */
    private Long pendingCount;

    /**
     * 处理中记录数
     */
    private Long processingCount;

    /**
     * 等待重试记录数
     */
    private Long retryCount;

    /**
     * 已完成记录数
     */
    private Long doneCount;

    /**
     * 死信记录数
     */
    private Long deadCount;

    /**
     * 拒绝受理记录数
     */
    private Long rejectedCount;

    /**
     * 未完成记录数
     */
    private Long unfinishedCount;

    /**
     * 未完成记录最大积压秒数
     */
    private Long maxPendingSeconds;

    /**
     * 管道是否健康
     */
    private Boolean healthy;

    /**
     * 管道状态：NO_DATA / PENDING / RETRY / DEAD / REJECTED / DONE
     */
    private String status;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;
}
