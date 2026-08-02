CREATE TABLE IF NOT EXISTS pisces_event_materialization (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    fact_kind VARCHAR(32) NOT NULL COMMENT '事实类型：EVENT / EXPOSURE',
    fact_id VARCHAR(64) NOT NULL COMMENT '事实ID：event_id / exposure_id',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    group_id VARCHAR(64) NOT NULL COMMENT '实验组ID',
    event_type VARCHAR(64) DEFAULT NULL COMMENT '事件类型，曝光为空',
    materialization_source VARCHAR(32) NOT NULL COMMENT '物化来源：INBOX / REPLAY_FULL / REPAIR_MATERIALIZATION',
    replay_job_id VARCHAR(64) DEFAULT NULL COMMENT '关联重放任务ID',
    materialized_at DATETIME NOT NULL COMMENT '物化完成时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_materialization_fact (fact_kind, fact_id),
    KEY idx_materialization_experiment (experiment_id, group_id, fact_kind),
    KEY idx_materialization_replay_job (replay_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces事件事实派生物化账本表';
