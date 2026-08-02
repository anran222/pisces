CREATE TABLE IF NOT EXISTS pisces_experiment_exposure (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    exposure_id VARCHAR(64) NOT NULL COMMENT '曝光事实ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    visitor_id VARCHAR(128) NOT NULL COMMENT '访客ID',
    group_id VARCHAR(64) NOT NULL COMMENT '实验组ID',
    scene VARCHAR(128) DEFAULT NULL COMMENT '曝光场景',
    properties_json LONGTEXT DEFAULT NULL COMMENT '曝光属性JSON',
    idempotency_key VARCHAR(255) NOT NULL COMMENT '幂等键',
    exposed_at DATETIME NOT NULL COMMENT '曝光时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_exposure_id (exposure_id),
    UNIQUE KEY uk_exposure_idempotency (idempotency_key),
    KEY idx_exposure_exp_group (experiment_id, group_id),
    KEY idx_exposure_replay_scope (experiment_id, group_id, exposed_at),
    KEY idx_exposure_exp_visitor (experiment_id, visitor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验曝光事实表';
