create database if not exists pisces;

CREATE TABLE IF NOT EXISTS pisces_experiment_config (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    config_version BIGINT NOT NULL DEFAULT 1 COMMENT '配置版本',
    metadata_json LONGTEXT NOT NULL COMMENT '实验配置JSON',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_experiment_id (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验配置表';
