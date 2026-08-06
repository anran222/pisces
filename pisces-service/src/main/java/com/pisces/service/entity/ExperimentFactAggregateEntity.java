package com.pisces.service.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实验事实聚合结果
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/8/6 14:37
 */
@Data
public class ExperimentFactAggregateEntity {

    private Long totalCount;

    private LocalDateTime latestActivityAt;
}
