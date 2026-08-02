package com.pisces.service.service;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审批升级告警投递目标
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/17 15:25
 */
@Getter
@AllArgsConstructor
public class ApprovalEscalationNotificationTarget {

    private String targetKey;

    private String channelName;

    private String targetEndpoint;
}
