CREATE TABLE IF NOT EXISTS pisces_experiment_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    event_id VARCHAR(64) NOT NULL COMMENT '事件事实ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    visitor_id VARCHAR(128) NOT NULL COMMENT '访客ID',
    group_id VARCHAR(64) NOT NULL COMMENT '实验组ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    event_name VARCHAR(128) DEFAULT NULL COMMENT '事件名称',
    client_event_id VARCHAR(128) DEFAULT NULL COMMENT '客户端幂等事件ID',
    properties_json LONGTEXT DEFAULT NULL COMMENT '事件属性JSON',
    event_time DATETIME NOT NULL COMMENT '事件时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    UNIQUE KEY uk_client_event_id (client_event_id),
    KEY idx_event_exp_group_type (experiment_id, group_id, event_type),
    KEY idx_event_exp_visitor (experiment_id, visitor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验事件事实表';
