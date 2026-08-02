package com.pisces.service.audit;

/**
 * 审计日志常量
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:56
 */
public final class AuditLogConstants {

    public static final String RESOURCE_TYPE_EXPERIMENT = "EXPERIMENT";
    public static final String OPERATOR_SYSTEM = "system";
    public static final String STATUS_DELETED = "DELETED";

    public static final String ACTION_EXPERIMENT_CREATE = "EXPERIMENT_CREATE";
    public static final String ACTION_EXPERIMENT_UPDATE = "EXPERIMENT_UPDATE";
    public static final String ACTION_EXPERIMENT_START = "EXPERIMENT_START";
    public static final String ACTION_EXPERIMENT_STOP = "EXPERIMENT_STOP";
    public static final String ACTION_EXPERIMENT_PAUSE = "EXPERIMENT_PAUSE";
    public static final String ACTION_EXPERIMENT_RESUME = "EXPERIMENT_RESUME";
    public static final String ACTION_EXPERIMENT_DELETE = "EXPERIMENT_DELETE";
    public static final String ACTION_EXPERIMENT_APPROVAL_UPDATE = "EXPERIMENT_APPROVAL_UPDATE";
    public static final String ACTION_EXPERIMENT_CONFIG_PUBLISH = "EXPERIMENT_CONFIG_PUBLISH";
    public static final String ACTION_EXPERIMENT_CONFIG_ROLLBACK = "EXPERIMENT_CONFIG_ROLLBACK";
    public static final String ACTION_EXPERIMENT_CONFIG_DRAFT_SAVE = "EXPERIMENT_CONFIG_DRAFT_SAVE";
    public static final String ACTION_EXPERIMENT_CONFIG_DRAFT_PUBLISH = "EXPERIMENT_CONFIG_DRAFT_PUBLISH";
    public static final String ACTION_CONCLUSION_STATUS_UPDATE = "CONCLUSION_STATUS_UPDATE";

    private AuditLogConstants() {
    }
}
