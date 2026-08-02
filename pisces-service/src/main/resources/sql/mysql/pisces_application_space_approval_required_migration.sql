SET @approval_required_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'is_approval_required'
);

SET @approval_required_column_ddl = IF(
    @approval_required_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN is_approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''配置发布/启动实验是否需要审批：1是，0否'' AFTER experiment_quota',
    'SELECT ''pisces_application_space.is_approval_required already exists'''
);

PREPARE approval_required_column_statement FROM @approval_required_column_ddl;
EXECUTE approval_required_column_statement;
DEALLOCATE PREPARE approval_required_column_statement;

SET @approval_owners_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'approval_owners'
);

SET @approval_owners_column_ddl = IF(
    @approval_owners_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN approval_owners VARCHAR(512) DEFAULT NULL COMMENT ''配置发布/启动审批人列表，英文逗号分隔'' AFTER is_approval_required',
    'SELECT ''pisces_application_space.approval_owners already exists'''
);

PREPARE approval_owners_column_statement FROM @approval_owners_column_ddl;
EXECUTE approval_owners_column_statement;
DEALLOCATE PREPARE approval_owners_column_statement;

SET @approval_required_count_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'approval_required_count'
);

SET @approval_required_count_column_ddl = IF(
    @approval_required_count_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN approval_required_count INT NOT NULL DEFAULT 1 COMMENT ''审批通过所需人数'' AFTER approval_owners',
    'SELECT ''pisces_application_space.approval_required_count already exists'''
);

PREPARE approval_required_count_column_statement FROM @approval_required_count_column_ddl;
EXECUTE approval_required_count_column_statement;
DEALLOCATE PREPARE approval_required_count_column_statement;

SET @approval_policy_version_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'approval_policy_version'
);

SET @approval_policy_version_column_ddl = IF(
    @approval_policy_version_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN approval_policy_version BIGINT NOT NULL DEFAULT 1 COMMENT ''审批策略版本'' AFTER approval_required_count',
    'SELECT ''pisces_application_space.approval_policy_version already exists'''
);

PREPARE approval_policy_version_column_statement FROM @approval_policy_version_column_ddl;
EXECUTE approval_policy_version_column_statement;
DEALLOCATE PREPARE approval_policy_version_column_statement;

SET @approval_sla_hours_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'approval_sla_hours'
);

SET @approval_sla_hours_column_ddl = IF(
    @approval_sla_hours_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN approval_sla_hours INT DEFAULT NULL COMMENT ''审批SLA小时数，NULL表示不启用'' AFTER approval_policy_version',
    'SELECT ''pisces_application_space.approval_sla_hours already exists'''
);

PREPARE approval_sla_hours_column_statement FROM @approval_sla_hours_column_ddl;
EXECUTE approval_sla_hours_column_statement;
DEALLOCATE PREPARE approval_sla_hours_column_statement;

SET @approval_escalation_owners_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'approval_escalation_owners'
);

SET @approval_escalation_owners_column_ddl = IF(
    @approval_escalation_owners_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN approval_escalation_owners VARCHAR(512) DEFAULT NULL COMMENT ''审批升级接收人列表，英文逗号分隔'' AFTER approval_sla_hours',
    'SELECT ''pisces_application_space.approval_escalation_owners already exists'''
);

PREPARE approval_escalation_owners_column_statement FROM @approval_escalation_owners_column_ddl;
EXECUTE approval_escalation_owners_column_statement;
DEALLOCATE PREPARE approval_escalation_owners_column_statement;

SET @release_window_enabled_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'is_release_window_enabled'
);

SET @release_window_enabled_column_ddl = IF(
    @release_window_enabled_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN is_release_window_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否启用发布窗口：1是，0否'' AFTER approval_policy_version',
    'SELECT ''pisces_application_space.is_release_window_enabled already exists'''
);

PREPARE release_window_enabled_column_statement FROM @release_window_enabled_column_ddl;
EXECUTE release_window_enabled_column_statement;
DEALLOCATE PREPARE release_window_enabled_column_statement;

SET @release_window_timezone_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'release_window_timezone'
);

SET @release_window_timezone_column_ddl = IF(
    @release_window_timezone_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN release_window_timezone VARCHAR(64) DEFAULT NULL COMMENT ''发布窗口时区，如 Asia/Shanghai'' AFTER is_release_window_enabled',
    'SELECT ''pisces_application_space.release_window_timezone already exists'''
);

PREPARE release_window_timezone_column_statement FROM @release_window_timezone_column_ddl;
EXECUTE release_window_timezone_column_statement;
DEALLOCATE PREPARE release_window_timezone_column_statement;

SET @release_window_days_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'release_window_days'
);

SET @release_window_days_column_ddl = IF(
    @release_window_days_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN release_window_days VARCHAR(64) DEFAULT NULL COMMENT ''发布窗口星期列表，1=周一，7=周日，英文逗号分隔'' AFTER release_window_timezone',
    'SELECT ''pisces_application_space.release_window_days already exists'''
);

PREPARE release_window_days_column_statement FROM @release_window_days_column_ddl;
EXECUTE release_window_days_column_statement;
DEALLOCATE PREPARE release_window_days_column_statement;

SET @release_window_start_time_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'release_window_start_time'
);

SET @release_window_start_time_column_ddl = IF(
    @release_window_start_time_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN release_window_start_time VARCHAR(5) DEFAULT NULL COMMENT ''发布窗口开始时间，格式HH:mm'' AFTER release_window_days',
    'SELECT ''pisces_application_space.release_window_start_time already exists'''
);

PREPARE release_window_start_time_column_statement FROM @release_window_start_time_column_ddl;
EXECUTE release_window_start_time_column_statement;
DEALLOCATE PREPARE release_window_start_time_column_statement;

SET @release_window_end_time_column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_application_space'
      AND COLUMN_NAME = 'release_window_end_time'
);

SET @release_window_end_time_column_ddl = IF(
    @release_window_end_time_column_exists = 0,
    'ALTER TABLE pisces_application_space ADD COLUMN release_window_end_time VARCHAR(5) DEFAULT NULL COMMENT ''发布窗口结束时间，格式HH:mm'' AFTER release_window_start_time',
    'SELECT ''pisces_application_space.release_window_end_time already exists'''
);

PREPARE release_window_end_time_column_statement FROM @release_window_end_time_column_ddl;
EXECUTE release_window_end_time_column_statement;
DEALLOCATE PREPARE release_window_end_time_column_statement;

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

CREATE TABLE IF NOT EXISTS pisces_experiment_approval_escalation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    escalation_id VARCHAR(64) NOT NULL COMMENT '审批升级告警ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    approval_type VARCHAR(32) NOT NULL COMMENT '审批类型：EXPERIMENT_START/CONFIG_DRAFT',
    draft_version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿版本，启动审批固定为0',
    app_id VARCHAR(128) NOT NULL COMMENT '应用ID',
    owner VARCHAR(128) DEFAULT NULL COMMENT '实验负责人',
    experiment_name VARCHAR(256) DEFAULT NULL COMMENT '实验名称',
    approval_submitted_at DATETIME NOT NULL COMMENT '审批提交时间',
    approval_elapsed_hours BIGINT NOT NULL DEFAULT 0 COMMENT '审批已等待小时数',
    approval_sla_hours INT NOT NULL COMMENT '审批SLA小时数',
    approval_sla_status VARCHAR(32) NOT NULL COMMENT '审批SLA状态',
    escalation_owners VARCHAR(512) DEFAULT NULL COMMENT '升级接收人列表，英文逗号分隔',
    escalation_reason VARCHAR(512) DEFAULT NULL COMMENT '升级原因',
    notification_channel VARCHAR(64) NOT NULL COMMENT '告警消息通道',
    notification_payload_json TEXT DEFAULT NULL COMMENT '告警消息载荷JSON',
    notification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '告警消息投递状态：PENDING/DISPATCHING/SENT/RETRY/DEAD',
    notification_attempt_count INT NOT NULL DEFAULT 0 COMMENT '告警消息投递尝试次数',
    notification_last_attempt_at DATETIME DEFAULT NULL COMMENT '最近一次投递尝试时间',
    notification_next_attempt_at DATETIME DEFAULT NULL COMMENT '下一次投递时间',
    notification_delivered_at DATETIME DEFAULT NULL COMMENT '投递成功时间',
    notification_last_error VARCHAR(1024) DEFAULT NULL COMMENT '最近一次投递失败原因',
    escalation_status VARCHAR(32) NOT NULL COMMENT '升级告警状态：OPEN/ACKNOWLEDGED/RESOLVED',
    acknowledged_by VARCHAR(128) DEFAULT NULL COMMENT '确认人',
    acknowledged_comment VARCHAR(512) DEFAULT NULL COMMENT '确认备注',
    acknowledged_at DATETIME DEFAULT NULL COMMENT '确认时间',
    resolved_by VARCHAR(128) DEFAULT NULL COMMENT '关闭人',
    resolved_reason VARCHAR(512) DEFAULT NULL COMMENT '关闭原因',
    resolved_at DATETIME DEFAULT NULL COMMENT '关闭时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_escalation_id (escalation_id),
    UNIQUE KEY uk_approval_task_submission (experiment_id, approval_type, draft_version, approval_submitted_at),
    KEY idx_app_status_updated_at (app_id, escalation_status, updated_at),
    KEY idx_notification_status_next_attempt (notification_status, notification_next_attempt_at),
    KEY idx_task_status (experiment_id, approval_type, draft_version, escalation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验审批升级告警记录表';

SET @approval_escalation_table_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
);

SET @approval_escalation_notification_status_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_status'
);

SET @approval_escalation_notification_status_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_status_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' COMMENT ''告警消息投递状态：PENDING/DISPATCHING/SENT/RETRY/DEAD'' AFTER notification_payload_json',
    'SELECT ''pisces_experiment_approval_escalation.notification_status already exists or table missing'''
);

PREPARE approval_escalation_notification_status_statement FROM @approval_escalation_notification_status_ddl;
EXECUTE approval_escalation_notification_status_statement;
DEALLOCATE PREPARE approval_escalation_notification_status_statement;

SET @approval_escalation_notification_attempt_count_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_attempt_count'
);

SET @approval_escalation_notification_attempt_count_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_attempt_count_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_attempt_count INT NOT NULL DEFAULT 0 COMMENT ''告警消息投递尝试次数'' AFTER notification_status',
    'SELECT ''pisces_experiment_approval_escalation.notification_attempt_count already exists or table missing'''
);

PREPARE approval_escalation_notification_attempt_count_statement
    FROM @approval_escalation_notification_attempt_count_ddl;
EXECUTE approval_escalation_notification_attempt_count_statement;
DEALLOCATE PREPARE approval_escalation_notification_attempt_count_statement;

SET @approval_escalation_notification_last_attempt_at_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_last_attempt_at'
);

SET @approval_escalation_notification_last_attempt_at_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_last_attempt_at_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_last_attempt_at DATETIME DEFAULT NULL COMMENT ''最近一次投递尝试时间'' AFTER notification_attempt_count',
    'SELECT ''pisces_experiment_approval_escalation.notification_last_attempt_at already exists or table missing'''
);

PREPARE approval_escalation_notification_last_attempt_at_statement
    FROM @approval_escalation_notification_last_attempt_at_ddl;
EXECUTE approval_escalation_notification_last_attempt_at_statement;
DEALLOCATE PREPARE approval_escalation_notification_last_attempt_at_statement;

SET @approval_escalation_notification_next_attempt_at_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_next_attempt_at'
);

SET @approval_escalation_notification_next_attempt_at_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_next_attempt_at_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_next_attempt_at DATETIME DEFAULT NULL COMMENT ''下一次投递时间'' AFTER notification_last_attempt_at',
    'SELECT ''pisces_experiment_approval_escalation.notification_next_attempt_at already exists or table missing'''
);

PREPARE approval_escalation_notification_next_attempt_at_statement
    FROM @approval_escalation_notification_next_attempt_at_ddl;
EXECUTE approval_escalation_notification_next_attempt_at_statement;
DEALLOCATE PREPARE approval_escalation_notification_next_attempt_at_statement;

SET @approval_escalation_notification_delivered_at_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_delivered_at'
);

SET @approval_escalation_notification_delivered_at_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_delivered_at_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_delivered_at DATETIME DEFAULT NULL COMMENT ''投递成功时间'' AFTER notification_next_attempt_at',
    'SELECT ''pisces_experiment_approval_escalation.notification_delivered_at already exists or table missing'''
);

PREPARE approval_escalation_notification_delivered_at_statement
    FROM @approval_escalation_notification_delivered_at_ddl;
EXECUTE approval_escalation_notification_delivered_at_statement;
DEALLOCATE PREPARE approval_escalation_notification_delivered_at_statement;

SET @approval_escalation_notification_last_error_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND COLUMN_NAME = 'notification_last_error'
);

SET @approval_escalation_notification_last_error_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_last_error_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD COLUMN notification_last_error VARCHAR(1024) DEFAULT NULL COMMENT ''最近一次投递失败原因'' AFTER notification_delivered_at',
    'SELECT ''pisces_experiment_approval_escalation.notification_last_error already exists or table missing'''
);

PREPARE approval_escalation_notification_last_error_statement
    FROM @approval_escalation_notification_last_error_ddl;
EXECUTE approval_escalation_notification_last_error_statement;
DEALLOCATE PREPARE approval_escalation_notification_last_error_statement;

SET @approval_escalation_notification_index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_approval_escalation'
      AND INDEX_NAME = 'idx_notification_status_next_attempt'
);

SET @approval_escalation_notification_index_ddl = IF(
    @approval_escalation_table_exists > 0 AND @approval_escalation_notification_index_exists = 0,
    'ALTER TABLE pisces_experiment_approval_escalation ADD INDEX idx_notification_status_next_attempt (notification_status, notification_next_attempt_at)',
    'SELECT ''pisces_experiment_approval_escalation.idx_notification_status_next_attempt already exists or table missing'''
);

PREPARE approval_escalation_notification_index_statement
    FROM @approval_escalation_notification_index_ddl;
EXECUTE approval_escalation_notification_index_statement;
DEALLOCATE PREPARE approval_escalation_notification_index_statement;

CREATE TABLE IF NOT EXISTS pisces_experiment_approval_escalation_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    escalation_id VARCHAR(64) NOT NULL COMMENT '审批升级告警ID',
    channel_name VARCHAR(64) NOT NULL COMMENT '投递通道名',
    target_key VARCHAR(64) NOT NULL COMMENT '投递目标匿名标识',
    notification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '通道投递状态：PENDING/DISPATCHING/SENT/RETRY/DEAD',
    notification_attempt_count INT NOT NULL DEFAULT 0 COMMENT '通道投递尝试次数',
    notification_last_attempt_at DATETIME DEFAULT NULL COMMENT '最近一次通道投递尝试时间',
    notification_next_attempt_at DATETIME DEFAULT NULL COMMENT '下一次通道投递时间',
    notification_delivered_at DATETIME DEFAULT NULL COMMENT '通道投递成功时间',
    notification_last_error VARCHAR(1024) DEFAULT NULL COMMENT '最近一次通道投递失败原因',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否为当前启用目标',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_escalation_target (escalation_id, target_key),
    KEY idx_escalation_status (escalation_id, notification_status),
    KEY idx_status_next_attempt (notification_status, notification_next_attempt_at),
    KEY idx_active_updated_at (active, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pisces实验审批升级告警通道投递回执表';

SET @draft_approval_table_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_config_draft_approval'
);

SET @draft_approval_owners_snapshot_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_config_draft_approval'
      AND COLUMN_NAME = 'approval_owners_snapshot'
);

SET @draft_approval_owners_snapshot_ddl = IF(
    @draft_approval_table_exists > 0 AND @draft_approval_owners_snapshot_exists = 0,
    'ALTER TABLE pisces_experiment_config_draft_approval ADD COLUMN approval_owners_snapshot VARCHAR(512) DEFAULT NULL COMMENT ''审批人快照，英文逗号分隔'' AFTER approval_updated_at',
    'SELECT ''pisces_experiment_config_draft_approval.approval_owners_snapshot already exists or table missing'''
);

PREPARE draft_approval_owners_snapshot_statement FROM @draft_approval_owners_snapshot_ddl;
EXECUTE draft_approval_owners_snapshot_statement;
DEALLOCATE PREPARE draft_approval_owners_snapshot_statement;

SET @draft_approval_required_count_snapshot_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_config_draft_approval'
      AND COLUMN_NAME = 'approval_required_count_snapshot'
);

SET @draft_approval_required_count_snapshot_ddl = IF(
    @draft_approval_table_exists > 0 AND @draft_approval_required_count_snapshot_exists = 0,
    'ALTER TABLE pisces_experiment_config_draft_approval ADD COLUMN approval_required_count_snapshot INT NOT NULL DEFAULT 1 COMMENT ''审批通过人数快照'' AFTER approval_owners_snapshot',
    'SELECT ''pisces_experiment_config_draft_approval.approval_required_count_snapshot already exists or table missing'''
);

PREPARE draft_approval_required_count_snapshot_statement FROM @draft_approval_required_count_snapshot_ddl;
EXECUTE draft_approval_required_count_snapshot_statement;
DEALLOCATE PREPARE draft_approval_required_count_snapshot_statement;

SET @draft_approval_policy_version_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'pisces_experiment_config_draft_approval'
      AND COLUMN_NAME = 'approval_policy_version'
);

SET @draft_approval_policy_version_ddl = IF(
    @draft_approval_table_exists > 0 AND @draft_approval_policy_version_exists = 0,
    'ALTER TABLE pisces_experiment_config_draft_approval ADD COLUMN approval_policy_version BIGINT DEFAULT NULL COMMENT ''审批策略版本快照'' AFTER approval_required_count_snapshot',
    'SELECT ''pisces_experiment_config_draft_approval.approval_policy_version already exists or table missing'''
);

PREPARE draft_approval_policy_version_statement FROM @draft_approval_policy_version_ddl;
EXECUTE draft_approval_policy_version_statement;
DEALLOCATE PREPARE draft_approval_policy_version_statement;
