CREATE TABLE IF NOT EXISTS pisces_application_event_definition (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
    event_key VARCHAR(128) NOT NULL COMMENT '事件编码',
    label VARCHAR(128) NOT NULL COMMENT '事件名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '事件描述',
    category VARCHAR(64) DEFAULT NULL COMMENT '事件分类',
    is_primary TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否主事件：1是，0否',
    source_experiment_id VARCHAR(64) DEFAULT NULL COMMENT '来源实验ID',
    updated_by VARCHAR(128) NOT NULL COMMENT '更新人',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_event_key (app_id, event_key),
    KEY idx_app_updated_at (app_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces应用事件字典';

CREATE TABLE IF NOT EXISTS pisces_application_metric_definition (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
    metric_key VARCHAR(128) NOT NULL COMMENT '指标编码',
    name VARCHAR(128) NOT NULL COMMENT '指标名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '指标描述',
    aggregation_type VARCHAR(32) NOT NULL COMMENT '聚合类型',
    numerator_event_type VARCHAR(128) DEFAULT NULL COMMENT '分子事件类型',
    denominator_type VARCHAR(32) DEFAULT NULL COMMENT '分母类型',
    denominator_event_type VARCHAR(128) DEFAULT NULL COMMENT '分母事件类型',
    is_primary_metric TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否主指标：1是，0否',
    is_guardrail_metric TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否护栏指标：1是，0否',
    source_experiment_id VARCHAR(64) DEFAULT NULL COMMENT '来源实验ID',
    updated_by VARCHAR(128) NOT NULL COMMENT '更新人',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_metric_key (app_id, metric_key),
    KEY idx_app_updated_at (app_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces应用指标字典';
