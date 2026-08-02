CREATE TABLE IF NOT EXISTS pisces_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    audit_id VARCHAR(64) NOT NULL COMMENT '审计日志ID',
    resource_type VARCHAR(64) NOT NULL COMMENT '资源类型',
    resource_id VARCHAR(128) NOT NULL COMMENT '资源ID',
    action VARCHAR(64) NOT NULL COMMENT '操作类型',
    operator VARCHAR(128) NOT NULL COMMENT '操作人',
    before_status VARCHAR(64) DEFAULT NULL COMMENT '变更前状态',
    after_status VARCHAR(64) DEFAULT NULL COMMENT '变更后状态',
    summary VARCHAR(255) DEFAULT NULL COMMENT '操作摘要',
    detail_json LONGTEXT DEFAULT NULL COMMENT '操作详情JSON',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_id (audit_id),
    KEY idx_audit_resource_created (resource_type, resource_id, id),
    KEY idx_audit_action_created (action, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces管理操作审计日志表';
