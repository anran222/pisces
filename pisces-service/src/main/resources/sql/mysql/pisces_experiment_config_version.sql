create database if not exists pisces;

CREATE TABLE IF NOT EXISTS pisces_experiment_config_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    config_version BIGINT NOT NULL COMMENT '配置版本',
    metadata_json LONGTEXT NOT NULL COMMENT '实验配置快照JSON',
    published_by VARCHAR(128) NOT NULL DEFAULT 'system' COMMENT '发布人',
    publish_comment VARCHAR(512) DEFAULT NULL COMMENT '发布备注',
    source_config_version BIGINT DEFAULT NULL COMMENT '来源配置版本，回滚版本记录原始目标版本',
    source_type VARCHAR(32) NOT NULL DEFAULT 'PUBLISH' COMMENT '来源类型：PUBLISH/ROLLBACK',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_experiment_config_version (experiment_id, config_version),
    KEY idx_experiment_created_at (experiment_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验配置版本表';
