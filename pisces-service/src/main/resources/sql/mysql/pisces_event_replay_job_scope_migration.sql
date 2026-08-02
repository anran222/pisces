ALTER TABLE pisces_event_replay_job
    ADD COLUMN replay_mode VARCHAR(64) NOT NULL DEFAULT 'FULL_DERIVED_REBUILD' COMMENT '重放模式' AFTER active_key,
    ADD COLUMN scope_start_time DATETIME DEFAULT NULL COMMENT '重放范围开始时间' AFTER replay_mode,
    ADD COLUMN scope_end_time DATETIME DEFAULT NULL COMMENT '重放范围结束时间' AFTER scope_start_time,
    ADD COLUMN event_types_json VARCHAR(1024) DEFAULT NULL COMMENT '事件类型筛选JSON' AFTER scope_end_time,
    ADD COLUMN include_events TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否包含事件事实' AFTER event_types_json,
    ADD COLUMN include_exposures TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否包含曝光事实' AFTER include_events,
    ADD COLUMN full_derived_replay TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否等价全量派生重建' AFTER include_exposures;
