package com.pisces.service.event;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件收件箱记录
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
@Data
public class EventInboxRecord {

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

    private Map<String, Object> properties;

    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private String lockedBy;

    private LocalDateTime lockedUntil;

    private String lastError;

    private LocalDateTime eventTime;

    private LocalDateTime acceptedAt;

    private LocalDateTime processedAt;
}
