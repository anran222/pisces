package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件收件箱实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Data
public class EventInboxEntity {

    private Long id;

    private String inboxId;

    private String experimentId;

    private String visitorId;

    private String groupId;

    private String eventKind;

    private String eventType;

    private String eventName;

    private String scene;

    private String clientEventId;

    private String idempotencyKey;

    private String propertiesJson;

    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lockedBy;

    private LocalDateTime lockedUntil;

    private String lastError;

    private LocalDateTime eventTime;

    private LocalDateTime acceptedAt;

    private LocalDateTime processedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
