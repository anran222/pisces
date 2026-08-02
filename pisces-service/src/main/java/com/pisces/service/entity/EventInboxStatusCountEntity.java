package com.pisces.service.entity;

import lombok.Data;

/**
 * 事件收件箱状态计数实体
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:37
 */
@Data
public class EventInboxStatusCountEntity {

    private String status;

    private Long eventCount;
}
