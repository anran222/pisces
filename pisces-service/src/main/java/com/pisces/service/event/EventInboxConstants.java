package com.pisces.service.event;

/**
 * 事件收件箱常量
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:26
 */
public final class EventInboxConstants {

    public static final String KIND_EVENT = "EVENT";

    public static final String KIND_EXPOSURE = "EXPOSURE";

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_PROCESSING = "PROCESSING";

    public static final String STATUS_RETRY = "RETRY";

    public static final String STATUS_DONE = "DONE";

    public static final String STATUS_DEAD = "DEAD";

    public static final String STATUS_REJECTED = "REJECTED";

    private EventInboxConstants() {
    }
}
