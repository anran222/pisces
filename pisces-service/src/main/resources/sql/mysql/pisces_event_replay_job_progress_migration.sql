ALTER TABLE pisces_event_replay_job
    ADD COLUMN planned_affected_count BIGINT NOT NULL DEFAULT 0 COMMENT '计划处理总事实数' AFTER full_derived_replay,
    ADD COLUMN planned_event_count BIGINT NOT NULL DEFAULT 0 COMMENT '计划处理事件事实数' AFTER planned_affected_count,
    ADD COLUMN planned_exposure_count BIGINT NOT NULL DEFAULT 0 COMMENT '计划处理曝光事实数' AFTER planned_event_count,
    ADD COLUMN planned_group_count BIGINT NOT NULL DEFAULT 0 COMMENT '计划处理实验组数' AFTER planned_exposure_count;
