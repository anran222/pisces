package com.pisces.service.event;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件事实派生物化账本记录
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/30 11:03
 */
@Data
public class EventMaterializationRecord {

    public static final String FACT_KIND_EVENT = "EVENT";

    public static final String FACT_KIND_EXPOSURE = "EXPOSURE";

    public static final String SOURCE_INBOX = "INBOX";

    public static final String SOURCE_REPLAY_FULL = "REPLAY_FULL";

    public static final String SOURCE_REPLAY_COPY = "REPLAY_COPY";

    public static final String SOURCE_REPAIR_MATERIALIZATION = "REPAIR_MATERIALIZATION";

    private String factKind;

    private String factId;

    private String experimentId;

    private String groupId;

    private String eventType;

    private String materializationSource;

    private String replayJobId;

    private LocalDateTime materializedAt;
}
