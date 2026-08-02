create database if not exists pisces;

CREATE TABLE IF NOT EXISTS pisces_experiment_config_draft_approval (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    draft_version BIGINT NOT NULL COMMENT '草稿版本',
    base_config_version BIGINT NOT NULL COMMENT '草稿基线配置版本',
    approval_status VARCHAR(32) NOT NULL COMMENT '审批状态：NOT_REQUIRED/PENDING/APPROVED/REJECTED',
    requested_by VARCHAR(128) NOT NULL DEFAULT 'system' COMMENT '草稿提交人',
    draft_comment VARCHAR(512) DEFAULT NULL COMMENT '草稿备注',
    approval_operator VARCHAR(128) DEFAULT NULL COMMENT '审批操作人',
    approval_comment VARCHAR(512) DEFAULT NULL COMMENT '审批备注',
    approval_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批更新时间',
    approval_owners_snapshot VARCHAR(512) DEFAULT NULL COMMENT '审批人快照，英文逗号分隔',
    approval_required_count_snapshot INT NOT NULL DEFAULT 1 COMMENT '审批通过人数快照',
    approval_policy_version BIGINT DEFAULT NULL COMMENT '审批策略版本快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_experiment_draft_version (experiment_id, draft_version),
    KEY idx_experiment_status_updated_at (experiment_id, approval_status, approval_updated_at),
    KEY idx_status_updated_at (approval_status, approval_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验配置草稿审批记录表';
