package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件事实派生物化账本实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 11:03
 */
@Data
public class EventMaterializationEntity {

    private Long id;

    private String factKind;

    private String factId;

    private String experimentId;

    private String groupId;

    private String eventType;

    private String materializationSource;

    private String replayJobId;

    private LocalDateTime materializedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
