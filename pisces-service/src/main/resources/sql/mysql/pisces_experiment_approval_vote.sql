create database if not exists pisces;

CREATE TABLE IF NOT EXISTS pisces_experiment_approval_vote (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    approval_type VARCHAR(32) NOT NULL COMMENT '审批类型：EXPERIMENT_START/CONFIG_DRAFT',
    draft_version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿版本，启动审批固定为0',
    approval_status VARCHAR(32) NOT NULL COMMENT '审批状态：APPROVED/REJECTED',
    approval_operator VARCHAR(128) NOT NULL COMMENT '审批操作人',
    approval_comment VARCHAR(512) DEFAULT NULL COMMENT '审批备注',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_operator (experiment_id, approval_type, draft_version, approval_operator),
    KEY idx_approval_task_status (experiment_id, approval_type, draft_version, approval_status),
    KEY idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验审批投票记录表';
