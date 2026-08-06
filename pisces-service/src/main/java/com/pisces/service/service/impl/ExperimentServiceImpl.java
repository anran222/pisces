package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;
import com.pisces.common.model.ApplicationSpace;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentApprovalEscalation;
import com.pisces.common.model.ExperimentApprovalEscalationDelivery;
import com.pisces.common.model.ExperimentApprovalEscalationNotificationStatus;
import com.pisces.common.model.ExperimentApprovalEscalationStatus;
import com.pisces.common.model.ExperimentApprovalTaskType;
import com.pisces.common.model.ExperimentApprovalVote;
import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.request.ExperimentApprovalEscalationAcknowledgeRequest;
import com.pisces.common.request.ExperimentApprovalStatusUpdateRequest;
import com.pisces.common.request.ExperimentConfigDraftSaveRequest;
import com.pisces.common.request.ExperimentConfigPublishRequest;
import com.pisces.common.request.ExperimentConfigRollbackRequest;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.AuditLogResponse;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.common.response.ExperimentApprovalEscalationDeliveryResponse;
import com.pisces.common.response.ExperimentApprovalEscalationOperationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationStatusResponse;
import com.pisces.common.response.ExperimentApprovalTaskResponse;
import com.pisces.common.response.ExperimentConfigDraftApprovalResponse;
import com.pisces.common.response.ExperimentConfigDraftResponse;
import com.pisces.common.response.ExperimentConfigVersionResponse;
import com.pisces.common.response.ExperimentPreflightResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.audit.AuditLogConstants;
import com.pisces.service.audit.AuditLogRecord;
import com.pisces.service.conclusion.ExperimentConclusionStatusPolicy;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ApplicationSpaceRepository;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.repository.ExperimentApprovalVoteRepository;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import com.pisces.service.service.AuditLogService;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.ApplicationDictionaryService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.validation.ExperimentPreflightValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 实验管理服务实现
 */
@Slf4j
@Service
public class ExperimentServiceImpl implements ExperimentService {

    private static final Pattern DEFINITION_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private static final long EXPERIMENT_START_DRAFT_VERSION = 0L;

    private static final int DEFAULT_APPROVAL_REQUIRED_COUNT = 1;

    private static final String APPROVAL_REJECTED_DEFAULT_COMMENT = "审批拒绝";

    private static final double APPROVAL_SLA_DUE_SOON_RATIO = 0.8D;

    private static final String APPROVAL_SLA_STATUS_ON_TRACK = "ON_TRACK";

    private static final String APPROVAL_SLA_STATUS_DUE_SOON = "DUE_SOON";

    private static final String APPROVAL_SLA_STATUS_OVERDUE = "OVERDUE";

    private static final String APPROVAL_ESCALATION_NOTIFICATION_CHANNEL = "APPROVAL_ESCALATION_OUTBOX";

    private static final int APPROVAL_ESCALATION_ID_LENGTH = 12;

    private static final int DEFAULT_APPROVAL_ESCALATION_QUERY_LIMIT = 200;

    private static final String APPROVAL_ESCALATION_STATUS_NO_DATA = "NO_DATA";

    private static final String APPROVAL_ESCALATION_STATUS_PENDING = "PENDING";

    private static final String APPROVAL_ESCALATION_STATUS_RETRY = "RETRY";

    private static final String APPROVAL_ESCALATION_STATUS_DEAD = "DEAD";

    private static final String APPROVAL_ESCALATION_STATUS_SENT = "SENT";

    private static final String APPROVAL_ESCALATION_OPERATION_RETRY_DEAD = "RETRY_DEAD_NOTIFICATION";

    private static final String APPROVAL_ESCALATION_OPERATION_SUCCESS = "SUCCESS";

    private static final String APPROVAL_RISK_LEVEL_UNKNOWN = "UNKNOWN";

    private static final String APPROVAL_RISK_LEVEL_CLEAR = "CLEAR";

    private static final String APPROVAL_RISK_LEVEL_WARNING = "WARNING";

    private static final String APPROVAL_RISK_LEVEL_BLOCKED = "BLOCKED";

    private static final String APPROVAL_RISK_FLAG_ANALYSIS_NOT_READY = "ANALYSIS_NOT_READY";

    private static final String APPROVAL_RISK_FLAG_SRM = "SRM";

    private static final String APPROVAL_RISK_FLAG_GUARDRAIL_BREACHED = "GUARDRAIL_BREACHED";

    private static final String GUARDRAIL_STATUS_PASS = "PASS";

    private static final String GUARDRAIL_STATUS_BLOCKED = "BLOCKED";

    private static final String DEFAULT_RELEASE_WINDOW_TIMEZONE = "Asia/Shanghai";

    private static final String DEFAULT_RELEASE_WINDOW_START_TIME = "09:00";

    private static final String DEFAULT_RELEASE_WINDOW_END_TIME = "18:00";

    private static final List<Integer> DEFAULT_RELEASE_WINDOW_DAYS = List.of(1, 2, 3, 4, 5);

    private static final DateTimeFormatter RELEASE_WINDOW_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    private Clock releaseWindowClock = Clock.systemDefaultZone();

    private Clock approvalTaskClock = Clock.systemDefaultZone();

    @Autowired
    private ConfigService configService;

    @Autowired
    private TrafficRuleEvaluator trafficRuleEvaluator;

    @Autowired
    private AnalysisService analysisService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private ApplicationSpaceRepository applicationSpaceRepository;

    @Autowired(required = false)
    private ExperimentApprovalVoteRepository experimentApprovalVoteRepository;

    @Autowired(required = false)
    private ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    @Autowired(required = false)
    private ApprovalEscalationNotificationDispatcher approvalEscalationNotificationDispatcher;

    @Autowired(required = false)
    private ApplicationDictionaryService applicationDictionaryService;

    @Autowired
    private GroupConfigSchemaValidator groupConfigSchemaValidator;

    @Autowired
    private ExperimentPreflightValidator experimentPreflightValidator;

    /**
     * 执行实验创建前检查。
     *
     * @param request 实验草案
     * @return 创建前检查结果
     */
    @Override
    public ExperimentPreflightResponse preflightExperiment(ExperimentCreateRequest request) {
        List<ExperimentPreflightResponse.CheckItem> checks = new ArrayList<>();
        List<GroupConfigFieldDefinition> normalizedSchema;
        try {
            normalizedSchema = groupConfigSchemaValidator.normalizeSchema(
                    request == null ? null : request.getGroupConfigSchema());
        } catch (BusinessException exception) {
            normalizedSchema = List.of();
            checks.add(buildPreflightCheck("GROUP_SCHEMA", "字段与分组",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "字段定义需要修正", exception.getMessage(),
                    "请修正字段名称、类型或默认值", "schema"));
        }
        checks.addAll(experimentPreflightValidator.validate(request, normalizedSchema));

        String requestedAppId = request == null ? null : trimToNull(request.getAppId());
        String resolvedAppId = ApiKeyContextHolder.resolveCreateAppId(requestedAppId);
        ApplicationSpace applicationSpace = findApplicationSpace(resolvedAppId).orElse(null);
        int quotaUsed = applicationSpace == null ? 0 : countExperimentsByAppId(resolvedAppId);
        appendApplicationPreflightChecks(
                checks, request, requestedAppId, resolvedAppId, applicationSpace, quotaUsed);

        ExperimentPreflightResponse response = new ExperimentPreflightResponse();
        response.setChecks(checks);
        response.setBlockingCount((int) checks.stream()
                .filter(check -> ExperimentPreflightValidator.STATUS_BLOCKED.equals(check.getStatus()))
                .count());
        response.setWarningCount((int) checks.stream()
                .filter(check -> ExperimentPreflightValidator.STATUS_WARNING.equals(check.getStatus()))
                .count());
        response.setReadyToCreate(response.getBlockingCount() == 0);
        response.setSummary(buildPreflightSummary(request, resolvedAppId, applicationSpace));
        response.setApplicationGovernance(buildPreflightGovernance(applicationSpace, quotaUsed));
        return response;
    }

    /**
     * 创建实验（无用户系统版本）
     */
    @Override
    public Experiment createExperiment(ExperimentCreateRequest request) {
        List<GroupConfigFieldDefinition> groupConfigSchema =
                groupConfigSchemaValidator.normalizeSchema(request.getGroupConfigSchema());
        // 参数校验
        validateExperimentRequest(request, groupConfigSchema);
        experimentPreflightValidator.assertReady(
                experimentPreflightValidator.validate(request, groupConfigSchema));

        // 生成实验ID
        String experimentId = "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String appId = ApiKeyContextHolder.resolveCreateAppId(request.getAppId());
        ApplicationSpace applicationSpace = findApplicationSpace(appId).orElse(null);
        validateApplicationSpaceQuota(appId, applicationSpace);
        DefinitionSelection definitionSelection = resolveApplicationDictionarySelection(appId, request);
        String owner = resolveCreateOwner(request, applicationSpace);
        LocalDateTime now = LocalDateTime.now();

        // 创建实验对象
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setCreator(owner);
        experiment.setAppId(appId);
        experiment.setOwner(owner);
        experiment.setCreateTime(now);
        experiment.setUpdateTime(now);

        // 构建实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new LinkedHashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema,
                        groupConfig.getConfig(), groupConfig.getId()));
                groups.put(group.getId(), group);
            }
        }

        // 构建流量配置
        TrafficConfig trafficConfig = buildTrafficConfig(request.getTraffic());

        // 构建实验元数据
        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(1L);
        metadata.setLayerId(trimToNull(request.getLayerId()));
        metadata.setAppId(appId);
        metadata.setOwner(owner);
        metadata.setExperiment(experiment);
        metadata.setGroups(groups);
        metadata.setTraffic(trafficConfig);
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        metadata.setEventDefinitions(definitionSelection.eventDefinitions());
        metadata.setMetricDefinitions(definitionSelection.metricDefinitions());
        metadata.setGroupConfigSchema(groupConfigSchema);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.NOT_READY);
        metadata.setConclusionUpdatedAt(now);
        initializeApprovalStatus(metadata, applicationSpace, ApiKeyContextHolder.resolveOperator(owner), now);

        // 保存到Zookeeper
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        log.info("创建实验成功: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_CREATE,
                ApiKeyContextHolder.resolveOperator(owner), null, statusName(experiment.getStatus()), "创建实验",
                buildExperimentAuditDetail(metadata));
        return experiment;
    }

    /**
     * 更新实验（无用户系统版本）
     */
    @Override
    public Experiment updateExperiment(String experimentId, ExperimentCreateRequest request) {
        List<GroupConfigFieldDefinition> groupConfigSchema =
                groupConfigSchemaValidator.normalizeSchema(request.getGroupConfigSchema());
        validateExperimentRequest(request, groupConfigSchema);
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(metadata)).orElse(null);
        validateConfigChangeApproval(metadata, applicationSpace);
        DefinitionSelection definitionSelection = resolveApplicationDictionarySelection(
                ApiKeyContextHolder.resolveMetadataAppId(metadata), request);

        Experiment experiment = metadata.getExperiment();
        String beforeStatus = statusName(experiment.getStatus());
        long beforeConfigVersion = metadata.getConfigVersion();
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setUpdateTime(LocalDateTime.now());

        // 更新实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new LinkedHashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema,
                        groupConfig.getConfig(), groupConfig.getId()));
                groups.put(group.getId(), group);
            }
        }
        metadata.setGroups(groups);
        metadata.setLayerId(trimToNull(request.getLayerId()));

        // 更新流量配置
        TrafficConfig trafficConfig = buildTrafficConfig(request.getTraffic());
        metadata.setTraffic(trafficConfig);

        // 更新白名单和黑名单
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        metadata.setEventDefinitions(definitionSelection.eventDefinitions());
        metadata.setMetricDefinitions(definitionSelection.metricDefinitions());
        metadata.setGroupConfigSchema(groupConfigSchema);
        metadata.setConfigVersion(Math.max(1L, metadata.getConfigVersion()) + 1);
        resetConclusionAfterConfigChange(metadata);
        refreshApprovalStatusAfterConfigChange(metadata, applicationSpace);
        if (experiment.getStatus() == Experiment.ExperimentStatus.RUNNING) {
            validateReleaseWindow(applicationSpace, "更新运行中实验配置");
            validateNoRunningMutexConflict(experimentId, metadata);
        }

        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        log.info("更新实验成功: {}", experimentId);
        Map<String, Object> detail = buildExperimentAuditDetail(metadata);
        detail.put("beforeConfigVersion", beforeConfigVersion);
        detail.put("afterConfigVersion", metadata.getConfigVersion());
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_UPDATE,
                resolveCurrentOperator(), beforeStatus, statusName(experiment.getStatus()), "更新实验配置",
                detail);
        return experiment;
    }

    /**
     * 启动实验（无用户系统版本）
     */
    @Override
    public void startExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.DRAFT) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有草稿状态的实验才能启动");
        }
        validateActivationApproval(metadata);
        validateReleaseWindow(metadata, "启动实验");
        validateNoRunningMutexConflict(experimentId, metadata);
        synchronizeConclusionForActivation(metadata, "实验启动后自动进入运行中");

        String beforeStatus = statusName(experiment.getStatus());
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());

        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }

        log.info("启动实验: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_START,
                resolveCurrentOperator(), beforeStatus, statusName(experiment.getStatus()), "启动实验",
                buildExperimentAuditDetail(metadata));
    }

    /**
     * 停止实验（无用户系统版本）
     */
    @Override
    public void stopExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        Experiment experiment = metadata.getExperiment();
        String beforeStatus = statusName(experiment.getStatus());
        experiment.setStatus(Experiment.ExperimentStatus.STOPPED);
        experiment.setUpdateTime(LocalDateTime.now());

        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }

        log.info("停止实验: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_STOP,
                resolveCurrentOperator(), beforeStatus, statusName(experiment.getStatus()), "停止实验",
                buildExperimentAuditDetail(metadata));
    }

    /**
     * 暂停实验（无用户系统版本）
     */
    @Override
    public void pauseExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.RUNNING) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有运行中的实验才能暂停");
        }

        String beforeStatus = statusName(experiment.getStatus());
        experiment.setStatus(Experiment.ExperimentStatus.PAUSED);
        experiment.setUpdateTime(LocalDateTime.now());

        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }

        log.info("暂停实验: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_PAUSE,
                resolveCurrentOperator(), beforeStatus, statusName(experiment.getStatus()), "暂停实验",
                buildExperimentAuditDetail(metadata));
    }

    /**
     * 恢复实验（从暂停状态恢复到运行状态）
     */
    @Override
    public void resumeExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.PAUSED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有暂停状态的实验才能恢复");
        }
        validateActivationApproval(metadata);
        validateReleaseWindow(metadata, "恢复实验");
        validateNoRunningMutexConflict(experimentId, metadata);
        synchronizeConclusionForActivation(metadata, "实验恢复后重新进入运行中");

        String beforeStatus = statusName(experiment.getStatus());
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());

        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }

        log.info("恢复实验: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_RESUME,
                resolveCurrentOperator(), beforeStatus, statusName(experiment.getStatus()), "恢复实验",
                buildExperimentAuditDetail(metadata));
    }

    /**
     * 获取实验
     */
    @Override
    public ExperimentResponse getExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);
        enrichSuggestedConclusion(experimentId, metadata);
        return convertToResponse(metadata);
    }

    @Override
    public List<AuditLogResponse> listExperimentAuditLogs(String experimentId) {
        getExperimentMetadataOrThrow(experimentId);
        if (auditLogService == null) {
            return List.of();
        }
        return auditLogService.listExperimentAuditLogs(experimentId);
    }

    @Override
    public List<ExperimentConfigVersionResponse> listConfigVersions(String experimentId) {
        getExperimentMetadataOrThrow(experimentId);
        return configService.listExperimentConfigVersions(experimentId).stream()
                .map(this::convertToConfigVersionResponse)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentConfigVersionResponse publishConfigVersion(String experimentId,
                                                               ExperimentConfigPublishRequest request) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);
        String operator = ApiKeyContextHolder.resolveOperator(request == null ? null : request.getOperator());
        String comment = trimToNull(request == null ? null : request.getComment());
        ExperimentConfigVersion configVersion;
        try {
            configVersion = configService.saveExperimentConfigVersion(experimentId, metadata, operator, comment,
                    null, ExperimentConfigVersion.SOURCE_TYPE_PUBLISH);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "发布实验配置失败: " + exception.getMessage());
        }

        Map<String, Object> detail = buildExperimentAuditDetail(metadata);
        detail.put("publishComment", comment);
        detail.put("sourceType", ExperimentConfigVersion.SOURCE_TYPE_PUBLISH);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_CONFIG_PUBLISH,
                operator, statusName(metadata.getExperiment().getStatus()),
                statusName(metadata.getExperiment().getStatus()), "发布实验配置版本", detail);
        return convertToConfigVersionResponse(configVersion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentConfigVersionResponse rollbackConfigVersion(String experimentId,
                                                                ExperimentConfigRollbackRequest request) {
        validateRollbackRequest(request);
        ExperimentMetadata currentMetadata = getExperimentMetadataOrThrow(experimentId);
        long targetConfigVersion = request.getTargetConfigVersion();
        ExperimentConfigVersion targetVersion = configService.getExperimentConfigVersion(experimentId,
                        targetConfigVersion)
                .orElseThrow(() -> new BusinessException(ResponseCode.DATA_NOT_FOUND,
                        "实验配置版本不存在: " + targetConfigVersion));
        ExperimentMetadata rollbackMetadata = targetVersion.getMetadata();
        validateRollbackMetadata(experimentId, currentMetadata, rollbackMetadata, targetConfigVersion);

        long beforeConfigVersion = currentMetadata.getConfigVersion();
        long afterConfigVersion = Math.max(1L, beforeConfigVersion) + 1L;
        applyRollbackRuntimeFields(currentMetadata, rollbackMetadata, afterConfigVersion);
        resetConclusionAfterConfigChange(rollbackMetadata);
        validateReleaseWindow(rollbackMetadata, "回滚实验配置");
        if (rollbackMetadata.getExperiment().getStatus() == Experiment.ExperimentStatus.RUNNING) {
            validateNoRunningMutexConflict(experimentId, rollbackMetadata);
        }

        try {
            configService.saveExperimentConfig(experimentId, rollbackMetadata);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存回滚配置失败: " + exception.getMessage());
        }
        String operator = ApiKeyContextHolder.resolveOperator(request.getOperator());
        String comment = trimToNull(request.getComment());
        ExperimentConfigVersion rollbackVersion;
        try {
            rollbackVersion = configService.saveExperimentConfigVersion(experimentId, rollbackMetadata, operator,
                    comment, targetConfigVersion, ExperimentConfigVersion.SOURCE_TYPE_ROLLBACK);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "记录回滚配置版本失败: " + exception.getMessage());
        }

        Map<String, Object> detail = buildExperimentAuditDetail(rollbackMetadata);
        detail.put("beforeConfigVersion", beforeConfigVersion);
        detail.put("afterConfigVersion", afterConfigVersion);
        detail.put("rollbackFromConfigVersion", targetConfigVersion);
        detail.put("rollbackComment", comment);
        detail.put("sourceType", ExperimentConfigVersion.SOURCE_TYPE_ROLLBACK);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_CONFIG_ROLLBACK,
                operator, statusName(currentMetadata.getExperiment().getStatus()),
                statusName(rollbackMetadata.getExperiment().getStatus()), "回滚实验配置版本", detail);
        return convertToConfigVersionResponse(rollbackVersion);
    }

    @Override
    public ExperimentConfigDraftResponse getConfigDraft(String experimentId) {
        ExperimentMetadata currentMetadata = getExperimentMetadataOrThrow(experimentId);
        return configService.getExperimentConfigDraft(experimentId)
                .map(draft -> convertToConfigDraftResponse(currentMetadata, draft,
                        findDraftApproval(experimentId, draft.getDraftVersion()).orElse(null)))
                .orElse(null);
    }

    @Override
    public List<ExperimentConfigDraftApprovalResponse> listConfigDraftApprovals(String experimentId) {
        getExperimentMetadataOrThrow(experimentId);
        return configService.listExperimentConfigDraftApprovals(experimentId).stream()
                .map(this::convertToConfigDraftApprovalResponse)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentConfigDraftResponse saveConfigDraft(String experimentId, ExperimentConfigDraftSaveRequest request) {
        List<GroupConfigFieldDefinition> groupConfigSchema =
                groupConfigSchemaValidator.normalizeSchema(request.getGroupConfigSchema());
        validateExperimentRequest(request, groupConfigSchema);
        ExperimentMetadata currentMetadata = getExperimentMetadataOrThrow(experimentId);
        String operator = ApiKeyContextHolder.resolveOperator(request.getOperator());
        String comment = trimToNull(request.getComment());
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(currentMetadata)).orElse(null);
        refreshApprovalStatusAfterConfigDraftChange(experimentId, currentMetadata, applicationSpace, operator);
        ExperimentMetadata draftMetadata = buildDraftMetadata(currentMetadata, request, groupConfigSchema);

        ExperimentConfigDraft draft = configService.saveExperimentConfigDraft(experimentId, draftMetadata,
                currentMetadata.getConfigVersion(), operator, comment);
        ExperimentConfigDraftApproval draftApproval =
                saveDraftApproval(experimentId, draft, applicationSpace, operator, comment);
        Map<String, Object> detail = buildExperimentAuditDetail(draftMetadata);
        detail.put("baseConfigVersion", currentMetadata.getConfigVersion());
        detail.put("draftVersion", draft.getDraftVersion());
        detail.put("draftComment", comment);
        detail.put("draftApprovalStatus", approvalStatusName(draftApproval.getApprovalStatus()));
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_CONFIG_DRAFT_SAVE,
                operator, statusName(currentMetadata.getExperiment().getStatus()),
                statusName(currentMetadata.getExperiment().getStatus()), "保存实验配置草稿", detail);
        return convertToConfigDraftResponse(currentMetadata, draft, draftApproval);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExperimentConfigVersionResponse publishConfigDraft(String experimentId, ExperimentConfigPublishRequest request) {
        ExperimentMetadata currentMetadata = getExperimentMetadataOrThrow(experimentId);
        ExperimentConfigDraft draft = configService.getExperimentConfigDraft(experimentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DATA_NOT_FOUND, "实验配置草稿不存在"));
        validateDraftPublishBaseline(currentMetadata, draft);
        ExperimentMetadata draftMetadata = draft.getMetadata();
        validateDraftMetadata(experimentId, currentMetadata, draftMetadata);
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(currentMetadata)).orElse(null);
        Optional<ExperimentConfigDraftApproval> draftApproval =
                findDraftApproval(experimentId, draft.getDraftVersion());
        validateDraftPublishApproval(applicationSpace, draft, draftApproval);
        validateConfigChangeApproval(currentMetadata, applicationSpace);
        validateReleaseWindow(applicationSpace, "发布配置草稿");

        long beforeConfigVersion = currentMetadata.getConfigVersion();
        long afterConfigVersion = Math.max(1L, beforeConfigVersion) + 1L;
        applyDraftRuntimeFields(currentMetadata, draftMetadata, afterConfigVersion);
        resetConclusionAfterConfigChange(draftMetadata);
        if (draftMetadata.getExperiment().getStatus() == Experiment.ExperimentStatus.RUNNING) {
            validateNoRunningMutexConflict(experimentId, draftMetadata);
        }

        try {
            configService.saveExperimentConfig(experimentId, draftMetadata);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "发布实验配置草稿失败: " + exception.getMessage());
        }
        String operator = ApiKeyContextHolder.resolveOperator(request == null ? null : request.getOperator());
        String comment = trimToNull(request == null ? null : request.getComment());
        ExperimentConfigVersion publishedVersion;
        try {
            publishedVersion = configService.saveExperimentConfigVersion(experimentId, draftMetadata, operator,
                    comment, draft.getBaseConfigVersion(), ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH);
            configService.deleteExperimentConfigDraft(experimentId);
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "记录草稿发布版本失败: " + exception.getMessage());
        }

        Map<String, Object> detail = buildExperimentAuditDetail(draftMetadata);
        detail.put("beforeConfigVersion", beforeConfigVersion);
        detail.put("afterConfigVersion", afterConfigVersion);
        detail.put("baseConfigVersion", draft.getBaseConfigVersion());
        detail.put("draftVersion", draft.getDraftVersion());
        detail.put("publishComment", comment);
        detail.put("sourceType", ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_CONFIG_DRAFT_PUBLISH,
                operator, statusName(currentMetadata.getExperiment().getStatus()),
                statusName(draftMetadata.getExperiment().getStatus()), "发布实验配置草稿", detail);
        return convertToConfigVersionResponse(publishedVersion);
    }

    @Override
    public void updateConclusionStatus(String experimentId, ExperimentConclusionStatusUpdateRequest request) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        ExperimentMetadata.ConclusionStatus targetStatus;
        try {
            targetStatus = ExperimentMetadata.ConclusionStatus.ofOrThrow(request.getConclusionStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, e.getMessage());
        }

        ExperimentMetadata.ConclusionStatus currentStatus = metadata.getConclusionStatus() != null
                ? metadata.getConclusionStatus() : ExperimentMetadata.ConclusionStatus.NOT_READY;
        validateConclusionStatusTransition(currentStatus, targetStatus);
        ExperimentReportSnapshot latestReportSnapshot =
                validateConclusionEvidence(experimentId, metadata, request, targetStatus);

        LocalDateTime now = LocalDateTime.now();
        String operator = ApiKeyContextHolder.resolveOperator(request.getOperator());
        String comment = trimToNull(request.getComment());
        metadata.setConclusionStatus(targetStatus);
        metadata.setConclusionUpdatedAt(now);
        metadata.setConclusionOperator(operator);
        metadata.setConclusionComment(comment);
        if (requiresConclusionEvidence(targetStatus)) {
            metadata.setConclusionConfigVersion(metadata.getConfigVersion());
            metadata.setConclusionReportSnapshotVersion(latestReportSnapshot.getSnapshotVersion());
        } else {
            clearConclusionBinding(metadata);
            metadata.setConclusionOperator(operator);
            metadata.setConclusionComment(comment);
        }
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验结论状态失败: " + e.getMessage());
        }
        log.info("更新实验结论状态成功: experimentId={}, from={}, to={}, operator={}",
                experimentId, currentStatus, targetStatus, operator);
        Map<String, Object> detail = buildExperimentAuditDetail(metadata);
        detail.put("experimentStatus", statusName(metadata.getExperiment().getStatus()));
        detail.put("expectedConfigVersion", request.getExpectedConfigVersion());
        detail.put("requestedReportSnapshotVersion", request.getReportSnapshotVersion());
        detail.put("conclusionConfigVersion", metadata.getConclusionConfigVersion());
        detail.put("conclusionReportSnapshotVersion", metadata.getConclusionReportSnapshotVersion());
        detail.put("conclusionOperator", operator);
        detail.put("conclusionComment", comment);
        applyConclusionSnapshotAuditDetail(detail, latestReportSnapshot);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_CONCLUSION_STATUS_UPDATE,
                operator, conclusionStatusName(currentStatus), conclusionStatusName(targetStatus),
                "更新实验结论状态", detail);
    }

    @Override
    public void updateApprovalStatus(String experimentId, ExperimentApprovalStatusUpdateRequest request) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(metadata)).orElse(null);
        if (!isApprovalRequired(applicationSpace)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "当前应用未启用配置/启动审批");
        }

        ExperimentMetadata.ApprovalStatus targetStatus;
        try {
            targetStatus = ExperimentMetadata.ApprovalStatus.ofOrThrow(request.getApprovalStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, e.getMessage());
        }
        if (targetStatus != ExperimentMetadata.ApprovalStatus.APPROVED
                && targetStatus != ExperimentMetadata.ApprovalStatus.REJECTED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "审批操作仅支持 APPROVED 或 REJECTED");
        }

        String operator = ApiKeyContextHolder.resolveOperator(request.getOperator());
        Optional<ExperimentConfigDraftApproval> currentDraftApproval = findCurrentDraftApproval(experimentId);
        ExperimentMetadata.ApprovalStatus currentStatus =
                resolveCurrentApprovalStatus(metadata, applicationSpace, currentDraftApproval);
        validateApprovalStatusTransition(currentStatus, targetStatus);
        validateApprovalOperationPermission(metadata, applicationSpace, currentDraftApproval, operator);
        ApprovalRiskContext riskContext = resolveApprovalRiskContext(experimentId);
        validateApprovalRiskGate(targetStatus, request, riskContext);

        ExperimentApprovalTaskType approvalType = resolveCurrentApprovalType(currentDraftApproval);
        long draftVersion = resolveApprovalVoteDraftVersion(currentDraftApproval);
        String approvalComment = trimToNull(request.getComment());
        ExperimentApprovalVote currentVote = saveApprovalVote(experimentId, approvalType, draftVersion, targetStatus,
                operator, approvalComment);
        List<ExperimentApprovalVote> approvalVotes =
                listApprovalVotes(experimentId, approvalType, draftVersion, currentVote);
        List<String> approvalOwners = resolveApprovalOwnersForTask(metadata, applicationSpace, currentDraftApproval);
        int approvalRequiredCount = resolveApprovalRequiredCountForTask(metadata, applicationSpace,
                currentDraftApproval, approvalOwners);
        int approvalApprovedCount = countApprovalVotes(approvalVotes, ExperimentMetadata.ApprovalStatus.APPROVED);
        int approvalRejectedCount = countApprovalVotes(approvalVotes, ExperimentMetadata.ApprovalStatus.REJECTED);
        ExperimentMetadata.ApprovalStatus finalStatus =
                resolveAggregatedApprovalStatus(targetStatus, approvalApprovedCount, approvalRequiredCount);
        String finalComment = resolveAggregatedApprovalComment(finalStatus, approvalComment,
                approvalApprovedCount, approvalRequiredCount, approvalRejectedCount);
        String finalOperator =
                resolveAggregatedApprovalOperator(metadata, currentDraftApproval, operator, finalStatus);

        metadata.setApprovalStatus(finalStatus);
        metadata.setApprovalOperator(finalOperator);
        metadata.setApprovalComment(finalComment);
        metadata.setApprovalUpdatedAt(LocalDateTime.now());
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验审批状态失败: " + e.getMessage());
        }
        Optional<ExperimentConfigDraftApproval> updatedDraftApproval =
                updateCurrentDraftApprovalStatus(experimentId, currentDraftApproval, finalStatus, finalOperator,
                        finalComment);
        if (finalStatus != ExperimentMetadata.ApprovalStatus.PENDING) {
            resolveApprovalEscalationsForTask(experimentId, approvalType, draftVersion, operator,
                    "审批状态已变更为 " + finalStatus.name());
        }

        Map<String, Object> detail = buildExperimentAuditDetail(metadata);
        detail.put("approvalVoteStatus", targetStatus.name());
        detail.put("approvalFinalStatus", finalStatus.name());
        detail.put("approvalRequiredCount", approvalRequiredCount);
        detail.put("approvalApprovedCount", approvalApprovedCount);
        detail.put("approvalRejectedCount", approvalRejectedCount);
        detail.put("approvalComment", finalComment);
        applyApprovalRiskAuditDetail(detail, targetStatus, request, riskContext);
        updatedDraftApproval.ifPresent(draftApproval -> {
            detail.put("approvalType", ExperimentApprovalTaskType.CONFIG_DRAFT.name());
            detail.put("draftVersion", draftApproval.getDraftVersion());
            detail.put("baseConfigVersion", draftApproval.getBaseConfigVersion());
        });
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_APPROVAL_UPDATE,
                operator, approvalStatusName(currentStatus), approvalStatusName(finalStatus), "更新实验审批状态",
                detail);
    }

    private ExperimentMetadata getExperimentMetadataOrThrow(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        normalizeExperimentOwnership(metadata);
        ApiKeyContextHolder.assertCanAccess(metadata);
        return metadata;
    }

    private boolean isVisibleExperimentMetadata(ExperimentMetadata metadata) {
        if (metadata == null || metadata.getExperiment() == null) {
            return false;
        }
        normalizeExperimentOwnership(metadata);
        return ApiKeyContextHolder.canAccess(metadata);
    }

    private void normalizeExperimentOwnership(ExperimentMetadata metadata) {
        String appId = ApiKeyContextHolder.resolveMetadataAppId(metadata);
        String owner = ApiKeyContextHolder.resolveMetadataOwner(metadata);
        metadata.setAppId(appId);
        metadata.setOwner(owner);
        if (metadata.getExperiment() != null) {
            metadata.getExperiment().setAppId(appId);
            metadata.getExperiment().setOwner(owner);
            if (metadata.getExperiment().getCreator() == null || metadata.getExperiment().getCreator().isBlank()) {
                metadata.getExperiment().setCreator(owner);
            }
        }
    }

    private String resolveCurrentOperator() {
        return ApiKeyContextHolder.resolveOperator(AuditLogConstants.OPERATOR_SYSTEM);
    }

    private String resolveCreateOwner(ExperimentCreateRequest request, ApplicationSpace applicationSpace) {
        Optional<ApiKeyPrincipal> principalOptional = ApiKeyContextHolder.get();
        if (principalOptional.isPresent() && !ApiKeyContextHolder.isAdmin(principalOptional.get())) {
            return ApiKeyContextHolder.resolveCreateOwner(request.getOwner());
        }
        String requestedOwner = trimToNull(request.getOwner());
        if (requestedOwner != null) {
            return requestedOwner;
        }
        String defaultOwner = applicationSpace == null ? null : trimToNull(applicationSpace.getDefaultOwner());
        if (defaultOwner != null) {
            return defaultOwner;
        }
        return ApiKeyContextHolder.resolveCreateOwner(null);
    }

    private Optional<ApplicationSpace> findApplicationSpace(String appId) {
        if (applicationSpaceRepository == null) {
            return Optional.empty();
        }
        Optional<ApplicationSpace> applicationSpaceOptional = applicationSpaceRepository.findByAppId(appId);
        return applicationSpaceOptional == null ? Optional.empty() : applicationSpaceOptional;
    }

    private void appendApplicationPreflightChecks(
            List<ExperimentPreflightResponse.CheckItem> checks,
            ExperimentCreateRequest request,
            String requestedAppId,
            String resolvedAppId,
            ApplicationSpace applicationSpace,
            int quotaUsed) {
        if (requestedAppId == null) {
            checks.add(buildPreflightCheck("APPLICATION_ACCESS", "应用与字典",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "尚未选择应用",
                    "创建实验前需要明确所属应用",
                    "请选择已注册且有权访问的应用", "basics"));
        } else if (!requestedAppId.equals(resolvedAppId)) {
            checks.add(buildPreflightCheck("APPLICATION_ACCESS", "应用与字典",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "无权使用所选应用",
                    "当前访问身份只能操作应用“" + resolvedAppId + "”",
                    "请选择当前身份有权访问的应用", "basics"));
        } else if (applicationSpace == null) {
            checks.add(buildPreflightCheck("APPLICATION_ACCESS", "应用与字典",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "应用尚未注册",
                    "应用“" + resolvedAppId + "”没有注册记录",
                    "请先前往应用管理完成应用登记", "basics"));
        } else {
            checks.add(buildPreflightCheck("APPLICATION_ACCESS", "应用与字典",
                    ExperimentPreflightValidator.STATUS_PASS, "应用可用",
                    "实验将归属于“" + applicationSpace.getDisplayName() + "”", null, "basics"));
        }

        appendDictionaryPreflightCheck(checks, request, resolvedAppId);
        appendQuotaPreflightCheck(checks, applicationSpace, quotaUsed);
        appendGovernancePreflightChecks(checks, request, applicationSpace);
    }

    private void appendDictionaryPreflightCheck(
            List<ExperimentPreflightResponse.CheckItem> checks,
            ExperimentCreateRequest request,
            String appId) {
        if (request == null || request.getEventDefinitions() == null || request.getMetricDefinitions() == null) {
            return;
        }
        try {
            DefinitionSelection selection = resolveApplicationDictionarySelection(appId, request);
            checks.add(buildPreflightCheck("APPLICATION_DICTIONARY", "应用与字典",
                    ExperimentPreflightValidator.STATUS_PASS, "字典归属有效",
                    "已确认" + selection.eventDefinitions().size() + "个事件和"
                            + selection.metricDefinitions().size() + "个指标属于当前应用",
                    null, "dictionary"));
        } catch (BusinessException exception) {
            checks.add(buildPreflightCheck("APPLICATION_DICTIONARY", "应用与字典",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "字典选择需要修正", exception.getMessage(),
                    "请重新选择当前应用字典中的事件和指标", "dictionary"));
        }
    }

    private void appendQuotaPreflightCheck(
            List<ExperimentPreflightResponse.CheckItem> checks,
            ApplicationSpace applicationSpace,
            int quotaUsed) {
        if (applicationSpace == null || applicationSpace.getExperimentQuota() == null) {
            checks.add(buildPreflightCheck("APPLICATION_QUOTA", "治理策略",
                    ExperimentPreflightValidator.STATUS_PASS, "实验配额可用",
                    "当前应用未限制实验总数", null, "basics"));
            return;
        }
        int quota = applicationSpace.getExperimentQuota();
        if (quotaUsed >= quota) {
            checks.add(buildPreflightCheck("APPLICATION_QUOTA", "治理策略",
                    ExperimentPreflightValidator.STATUS_BLOCKED, "实验配额已用尽",
                    "当前已使用" + quotaUsed + "个实验，应用配额为" + quota + "个",
                    "请清理无效实验或调整应用配额", "basics"));
            return;
        }
        checks.add(buildPreflightCheck("APPLICATION_QUOTA", "治理策略",
                ExperimentPreflightValidator.STATUS_PASS, "实验配额可用",
                "当前剩余" + (quota - quotaUsed) + "个实验名额", null, "basics"));
    }

    private void appendGovernancePreflightChecks(
            List<ExperimentPreflightResponse.CheckItem> checks,
            ExperimentCreateRequest request,
            ApplicationSpace applicationSpace) {
        if (isApprovalRequired(applicationSpace)) {
            checks.add(buildPreflightCheck("START_APPROVAL", "治理策略",
                    ExperimentPreflightValidator.STATUS_WARNING, "启动前需要审批",
                    "实验可以创建为草稿，启动前需要应用审批人通过",
                    "创建后请在应用管理中完成启动审批", "basics"));
        } else {
            checks.add(buildPreflightCheck("START_APPROVAL", "治理策略",
                    ExperimentPreflightValidator.STATUS_PASS, "无需启动审批",
                    "当前应用未启用启动审批", null, "basics"));
        }
        if (applicationSpace != null
                && Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())
                && !isCurrentlyInReleaseWindow(applicationSpace)) {
            checks.add(buildPreflightCheck("RELEASE_WINDOW", "治理策略",
                    ExperimentPreflightValidator.STATUS_WARNING, "当前不在发布窗口",
                    "实验可以创建为草稿，但当前时间不能启动或发布运行配置",
                    "请在应用配置的发布窗口内执行启动操作", "basics"));
        } else {
            checks.add(buildPreflightCheck("RELEASE_WINDOW", "治理策略",
                    ExperimentPreflightValidator.STATUS_PASS, "发布时间条件正常",
                    applicationSpace != null && Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())
                            ? "当前处于应用发布窗口内" : "当前应用未限制发布窗口",
                    null, "basics"));
        }
        boolean hasAudienceList = request != null
                && ((request.getWhitelist() != null && !request.getWhitelist().isEmpty())
                || (request.getBlacklist() != null && !request.getBlacklist().isEmpty()));
        checks.add(hasAudienceList
                ? buildPreflightCheck("AUDIENCE_LIST", "流量", ExperimentPreflightValidator.STATUS_PASS,
                        "已配置定向名单", "白名单或黑名单将参与分流过滤", null, "groups")
                : buildPreflightCheck("AUDIENCE_LIST", "流量", ExperimentPreflightValidator.STATUS_WARNING,
                        "未配置定向名单", "实验仍可创建，将按普通流量规则分配用户",
                        "如需灰度验证，可配置白名单或黑名单", "groups"));
    }

    private ExperimentPreflightResponse.Summary buildPreflightSummary(
            ExperimentCreateRequest request,
            String resolvedAppId,
            ApplicationSpace applicationSpace) {
        ExperimentPreflightResponse.Summary summary = new ExperimentPreflightResponse.Summary();
        summary.setAppId(resolvedAppId);
        summary.setApplicationName(applicationSpace == null ? resolvedAppId : applicationSpace.getDisplayName());
        if (request == null) {
            return summary;
        }
        summary.setExperimentName(trimToNull(request.getName()));
        summary.setStartTime(request.getStartTime() == null ? null : request.getStartTime().toString());
        summary.setEndTime(request.getEndTime() == null ? null : request.getEndTime().toString());
        summary.setGroupCount(request.getGroups() == null ? 0 : request.getGroups().size());
        summary.setTotalTraffic(request.getTraffic() == null ? null : request.getTraffic().getTotalTraffic());
        summary.setEventCount(request.getEventDefinitions() == null ? 0 : request.getEventDefinitions().size());
        summary.setMetricCount(request.getMetricDefinitions() == null ? 0 : request.getMetricDefinitions().size());
        if (request.getMetricDefinitions() != null) {
            request.getMetricDefinitions().stream()
                    .filter(metric -> metric != null && Boolean.TRUE.equals(metric.getPrimaryMetric()))
                    .findFirst()
                    .ifPresent(metric -> {
                        summary.setPrimaryMetricKey(metric.getKey());
                        summary.setPrimaryMetricName(metric.getName());
                    });
        }
        return summary;
    }

    private ExperimentPreflightResponse.ApplicationGovernance buildPreflightGovernance(
            ApplicationSpace applicationSpace, int quotaUsed) {
        ExperimentPreflightResponse.ApplicationGovernance governance =
                new ExperimentPreflightResponse.ApplicationGovernance();
        if (applicationSpace == null) {
            return governance;
        }
        governance.setExperimentQuota(applicationSpace.getExperimentQuota());
        governance.setQuotaUsed(quotaUsed);
        governance.setQuotaRemaining(applicationSpace.getExperimentQuota() == null
                ? null : Math.max(0, applicationSpace.getExperimentQuota() - quotaUsed));
        governance.setApprovalRequired(isApprovalRequired(applicationSpace));
        governance.setReleaseWindowEnabled(Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled()));
        governance.setCurrentlyInReleaseWindow(isCurrentlyInReleaseWindow(applicationSpace));
        if (Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
            governance.setReleaseWindowDescription(formatReleaseWindow(
                    resolveReleaseWindowDays(applicationSpace),
                    resolveReleaseWindowStartTime(applicationSpace),
                    resolveReleaseWindowEndTime(applicationSpace),
                    resolveReleaseWindowZoneId(applicationSpace)));
        } else {
            governance.setReleaseWindowDescription("未限制发布窗口");
        }
        return governance;
    }

    private boolean isCurrentlyInReleaseWindow(ApplicationSpace applicationSpace) {
        if (applicationSpace == null || !Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
            return true;
        }
        ZoneId zoneId = resolveReleaseWindowZoneId(applicationSpace);
        ZonedDateTime now = ZonedDateTime.now(releaseWindowClock.withZone(zoneId));
        LocalTime currentTime = now.toLocalTime();
        return resolveReleaseWindowDays(applicationSpace).contains(now.getDayOfWeek().getValue())
                && !currentTime.isBefore(resolveReleaseWindowStartTime(applicationSpace))
                && currentTime.isBefore(resolveReleaseWindowEndTime(applicationSpace));
    }

    private ExperimentPreflightResponse.CheckItem buildPreflightCheck(
            String code,
            String section,
            String status,
            String title,
            String detail,
            String action,
            String targetPanel) {
        ExperimentPreflightResponse.CheckItem check = new ExperimentPreflightResponse.CheckItem();
        check.setCode(code);
        check.setSection(section);
        check.setStatus(status);
        check.setTitle(title);
        check.setDetail(detail);
        check.setAction(action);
        check.setTargetPanel(targetPanel);
        return check;
    }

    private void initializeApprovalStatus(ExperimentMetadata metadata, ApplicationSpace applicationSpace,
                                          String operator, LocalDateTime now) {
        if (isApprovalRequired(applicationSpace)) {
            metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
            metadata.setApprovalOperator(operator);
            metadata.setApprovalComment("实验创建后等待启动审批");
            applyApprovalPolicySnapshot(metadata, applicationSpace);
        } else {
            metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.NOT_REQUIRED);
            metadata.setApprovalOperator(null);
            metadata.setApprovalComment(null);
            clearApprovalPolicySnapshot(metadata);
        }
        metadata.setApprovalUpdatedAt(now);
    }

    private void refreshApprovalStatusAfterConfigChange(ExperimentMetadata metadata,
                                                        ApplicationSpace applicationSpace) {
        LocalDateTime now = LocalDateTime.now();
        if (isApprovalRequired(applicationSpace)) {
            metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
            metadata.setApprovalOperator(resolveCurrentOperator());
            metadata.setApprovalComment("实验配置变更后重新进入审批");
            applyApprovalPolicySnapshot(metadata, applicationSpace);
        } else {
            metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.NOT_REQUIRED);
            metadata.setApprovalOperator(null);
            metadata.setApprovalComment(null);
            clearApprovalPolicySnapshot(metadata);
        }
        metadata.setApprovalUpdatedAt(now);
    }

    private void refreshApprovalStatusAfterConfigDraftChange(String experimentId, ExperimentMetadata metadata,
                                                             ApplicationSpace applicationSpace, String operator) {
        if (!isApprovalRequired(applicationSpace)) {
            return;
        }
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator(operator);
        metadata.setApprovalComment("配置草稿保存后等待发布审批");
        applyApprovalPolicySnapshot(metadata, applicationSpace);
        metadata.setApprovalUpdatedAt(LocalDateTime.now());
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存配置草稿审批状态失败: " + e.getMessage());
        }
    }

    private void validateConfigChangeApproval(ExperimentMetadata metadata, ApplicationSpace applicationSpace) {
        Experiment experiment = metadata.getExperiment();
        if (experiment != null && experiment.getStatus() == Experiment.ExperimentStatus.RUNNING
                && isApprovalRequired(applicationSpace)) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "启用审批的应用不允许直接更新运行中实验，请先暂停实验，审批通过后再恢复");
        }
    }

    private void validateActivationApproval(ExperimentMetadata metadata) {
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(metadata)).orElse(null);
        if (!isApprovalRequired(applicationSpace)) {
            return;
        }
        ExperimentMetadata.ApprovalStatus approvalStatus = resolveApprovalStatus(metadata, applicationSpace);
        if (approvalStatus != ExperimentMetadata.ApprovalStatus.APPROVED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "实验启动前需要审批通过，当前审批状态: " + approvalStatus);
        }
    }

    private void validateReleaseWindow(ExperimentMetadata metadata, String operationName) {
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(metadata)).orElse(null);
        validateReleaseWindow(applicationSpace, operationName);
    }

    private void validateReleaseWindow(ApplicationSpace applicationSpace, String operationName) {
        if (applicationSpace == null || !Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
            return;
        }
        ZoneId zoneId = resolveReleaseWindowZoneId(applicationSpace);
        List<Integer> releaseWindowDays = resolveReleaseWindowDays(applicationSpace);
        LocalTime startTime = resolveReleaseWindowStartTime(applicationSpace);
        LocalTime endTime = resolveReleaseWindowEndTime(applicationSpace);
        ZonedDateTime now = ZonedDateTime.now(releaseWindowClock.withZone(zoneId));
        int currentDay = now.getDayOfWeek().getValue();
        LocalTime currentTime = now.toLocalTime();
        boolean inWindow = releaseWindowDays.contains(currentDay)
                && !currentTime.isBefore(startTime)
                && currentTime.isBefore(endTime);
        if (!inWindow) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    operationName + "不在应用发布窗口内，允许窗口: "
                            + formatReleaseWindow(releaseWindowDays, startTime, endTime, zoneId)
                            + "，当前时间: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        }
    }

    private ZoneId resolveReleaseWindowZoneId(ApplicationSpace applicationSpace) {
        String timezone = trimToNull(applicationSpace.getReleaseWindowTimezone());
        try {
            return ZoneId.of(timezone != null ? timezone : DEFAULT_RELEASE_WINDOW_TIMEZONE);
        } catch (DateTimeException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "应用发布窗口时区配置无效: " + applicationSpace.getReleaseWindowTimezone());
        }
    }

    private List<Integer> resolveReleaseWindowDays(ApplicationSpace applicationSpace) {
        List<Integer> releaseWindowDays = applicationSpace.getReleaseWindowDays();
        if (releaseWindowDays == null || releaseWindowDays.isEmpty()) {
            return DEFAULT_RELEASE_WINDOW_DAYS;
        }
        for (Integer releaseWindowDay : releaseWindowDays) {
            if (releaseWindowDay == null || releaseWindowDay < 1 || releaseWindowDay > 7) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "应用发布窗口星期配置无效: " + releaseWindowDay);
            }
        }
        return releaseWindowDays.stream()
                .distinct()
                .toList();
    }

    private LocalTime resolveReleaseWindowStartTime(ApplicationSpace applicationSpace) {
        return parseReleaseWindowTime(applicationSpace.getReleaseWindowStartTime(), DEFAULT_RELEASE_WINDOW_START_TIME);
    }

    private LocalTime resolveReleaseWindowEndTime(ApplicationSpace applicationSpace) {
        LocalTime startTime = resolveReleaseWindowStartTime(applicationSpace);
        LocalTime endTime = parseReleaseWindowTime(applicationSpace.getReleaseWindowEndTime(),
                DEFAULT_RELEASE_WINDOW_END_TIME);
        if (!startTime.isBefore(endTime)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "应用发布窗口开始时间必须早于结束时间");
        }
        return endTime;
    }

    private LocalTime parseReleaseWindowTime(String releaseWindowTime, String defaultReleaseWindowTime) {
        String normalizedTime = trimToNull(releaseWindowTime);
        try {
            return LocalTime.parse(normalizedTime != null ? normalizedTime : defaultReleaseWindowTime,
                    RELEASE_WINDOW_TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "应用发布窗口时间配置无效: " + releaseWindowTime);
        }
    }

    private String formatReleaseWindow(List<Integer> releaseWindowDays, LocalTime startTime, LocalTime endTime,
                                       ZoneId zoneId) {
        String daysText = releaseWindowDays.stream()
                .map(this::formatReleaseWindowDay)
                .collect(Collectors.joining(","));
        return daysText + " " + startTime.format(RELEASE_WINDOW_TIME_FORMATTER)
                + "-" + endTime.format(RELEASE_WINDOW_TIME_FORMATTER) + " " + zoneId.getId();
    }

    private String formatReleaseWindowDay(Integer day) {
        return switch (day) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "未知";
        };
    }

    private void validateDraftPublishApproval(ApplicationSpace applicationSpace, ExperimentConfigDraft draft,
                                              Optional<ExperimentConfigDraftApproval> draftApproval) {
        if (!isApprovalRequired(applicationSpace)) {
            return;
        }
        if (draftApproval.isEmpty()) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "配置草稿审批记录不存在，请重新保存草稿后提交审批");
        }
        if (!Long.valueOf(draft.getDraftVersion()).equals(draftApproval.get().getDraftVersion())) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "配置草稿审批记录版本不一致: " + draftApproval.get().getDraftVersion());
        }
        ExperimentMetadata.ApprovalStatus approvalStatus = draftApproval.get().getApprovalStatus();
        if (approvalStatus != ExperimentMetadata.ApprovalStatus.APPROVED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "配置草稿发布前需要审批通过，当前审批状态: " + approvalStatus);
        }
    }

    private ExperimentConfigDraftApproval saveDraftApproval(String experimentId, ExperimentConfigDraft draft,
                                                            ApplicationSpace applicationSpace, String operator,
                                                            String draftComment) {
        ExperimentConfigDraftApproval approval = new ExperimentConfigDraftApproval();
        approval.setExperimentId(experimentId);
        approval.setDraftVersion(draft.getDraftVersion());
        approval.setBaseConfigVersion(draft.getBaseConfigVersion());
        approval.setApprovalStatus(resolveDraftInitialApprovalStatus(applicationSpace));
        approval.setRequestedBy(operator);
        approval.setDraftComment(draftComment);
        approval.setApprovalOperator(operator);
        approval.setApprovalComment(resolveDraftInitialApprovalComment(applicationSpace));
        applyDraftApprovalPolicySnapshot(approval, applicationSpace);
        return configService.saveExperimentConfigDraftApproval(approval);
    }

    private ExperimentMetadata.ApprovalStatus resolveDraftInitialApprovalStatus(ApplicationSpace applicationSpace) {
        return isApprovalRequired(applicationSpace)
                ? ExperimentMetadata.ApprovalStatus.PENDING : ExperimentMetadata.ApprovalStatus.NOT_REQUIRED;
    }

    private String resolveDraftInitialApprovalComment(ApplicationSpace applicationSpace) {
        return isApprovalRequired(applicationSpace) ? "配置草稿保存后等待发布审批" : null;
    }

    private void applyApprovalPolicySnapshot(ExperimentMetadata metadata, ApplicationSpace applicationSpace) {
        List<String> approvalOwners = resolveApprovalOwners(applicationSpace);
        metadata.setApprovalOwnersSnapshot(approvalOwners);
        metadata.setApprovalRequiredCountSnapshot(resolveApprovalRequiredCount(applicationSpace, approvalOwners));
        metadata.setApprovalPolicyVersion(resolveApprovalPolicyVersion(applicationSpace));
    }

    private void clearApprovalPolicySnapshot(ExperimentMetadata metadata) {
        metadata.setApprovalOwnersSnapshot(List.of());
        metadata.setApprovalRequiredCountSnapshot(null);
        metadata.setApprovalPolicyVersion(null);
    }

    private void resetConclusionAfterConfigChange(ExperimentMetadata metadata) {
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.NOT_READY);
        metadata.setConclusionUpdatedAt(LocalDateTime.now());
        clearConclusionBinding(metadata);
    }

    private void synchronizeConclusionForActivation(ExperimentMetadata metadata, String comment) {
        ExperimentMetadata.ConclusionStatus conclusionStatus = metadata.getConclusionStatus();
        if (conclusionStatus != null && conclusionStatus.isTerminal()) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "终态结论的实验不能重新运行");
        }
        if (conclusionStatus == ExperimentMetadata.ConclusionStatus.RUNNING) {
            return;
        }
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        metadata.setConclusionUpdatedAt(LocalDateTime.now());
        clearConclusionBinding(metadata);
        metadata.setConclusionOperator(resolveCurrentOperator());
        metadata.setConclusionComment(comment);
    }

    private void clearConclusionBinding(ExperimentMetadata metadata) {
        metadata.setConclusionConfigVersion(null);
        metadata.setConclusionReportSnapshotVersion(null);
        metadata.setConclusionOperator(null);
        metadata.setConclusionComment(null);
    }

    private ExperimentReportSnapshot validateConclusionEvidence(String experimentId,
                                                                ExperimentMetadata metadata,
                                                                ExperimentConclusionStatusUpdateRequest request,
                                                                ExperimentMetadata.ConclusionStatus targetStatus) {
        if (!requiresConclusionEvidence(targetStatus)) {
            return null;
        }
        Long expectedConfigVersion = request.getExpectedConfigVersion();
        if (expectedConfigVersion == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "确认实验结论必须提交当前配置版本");
        }
        long currentConfigVersion = Math.max(1L, metadata.getConfigVersion());
        if (!expectedConfigVersion.equals(currentConfigVersion)) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "人工结论基线配置版本已过期，当前版本: " + currentConfigVersion
                            + "，请求版本: " + expectedConfigVersion);
        }
        Integer requestedSnapshotVersion = request.getReportSnapshotVersion();
        if (requestedSnapshotVersion == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "确认实验结论必须提交报告快照版本");
        }
        ExperimentReportSnapshot latestSnapshot = findLatestReportSnapshot(experimentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                        "确认实验结论前需要先生成报告快照"));
        if (latestSnapshot.getSnapshotVersion() == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "最新报告快照缺少版本，无法确认结论");
        }
        if (!requestedSnapshotVersion.equals(latestSnapshot.getSnapshotVersion())) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "人工结论报告快照已过期，最新版本: " + latestSnapshot.getSnapshotVersion()
                            + "，请求版本: " + requestedSnapshotVersion);
        }
        validateConclusionReportGate(targetStatus, latestSnapshot, request);
        return latestSnapshot;
    }

    private boolean requiresConclusionEvidence(ExperimentMetadata.ConclusionStatus targetStatus) {
        return targetStatus == ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW
                || targetStatus.isTerminal();
    }

    private void validateConclusionReportGate(ExperimentMetadata.ConclusionStatus targetStatus,
                                              ExperimentReportSnapshot latestSnapshot,
                                              ExperimentConclusionStatusUpdateRequest request) {
        if (!Boolean.TRUE.equals(latestSnapshot.getAnalysisReady())) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "最新报告尚未满足分析门禁，不能确认实验结论");
        }
        List<String> breachedGuardrails = normalizeBreachedGuardrails(latestSnapshot.getBreachedGuardrails());
        if (targetStatus == ExperimentMetadata.ConclusionStatus.GRADUATED) {
            if (Boolean.TRUE.equals(latestSnapshot.getHasSrm())) {
                throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                        "最新报告存在 SRM，不能确认毕业结论");
            }
            if (!breachedGuardrails.isEmpty()) {
                throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                        "最新报告存在护栏异常，不能确认毕业结论: " + String.join(",", breachedGuardrails));
            }
        }
        ExperimentMetadata.ConclusionStatus suggestedStatus = latestSnapshot.getConclusionStatus();
        if (suggestedStatus != null && suggestedStatus != targetStatus && trimToNull(request.getComment()) == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "人工结论与最新报告建议不一致，必须填写结论备注");
        }
    }

    private void applyConclusionSnapshotAuditDetail(Map<String, Object> detail,
                                                    ExperimentReportSnapshot latestSnapshot) {
        if (latestSnapshot == null) {
            return;
        }
        detail.put("latestReportSnapshotVersion", latestSnapshot.getSnapshotVersion());
        detail.put("latestReportGeneratedAt", latestSnapshot.getGeneratedAt());
        detail.put("latestReportConclusionStatus", conclusionStatusName(latestSnapshot.getConclusionStatus()));
        detail.put("latestReportAnalysisReady", latestSnapshot.getAnalysisReady());
        detail.put("latestReportHasSrm", latestSnapshot.getHasSrm());
        detail.put("latestReportBreachedGuardrails", normalizeBreachedGuardrails(
                latestSnapshot.getBreachedGuardrails()));
        detail.put("latestReportPrimaryMetricKey", latestSnapshot.getPrimaryMetricKey());
        detail.put("latestReportBestPerformingGroup", latestSnapshot.getBestPerformingGroup());
        detail.put("latestReportWinningVariant", latestSnapshot.getWinningVariant());
    }

    private void applyDraftApprovalPolicySnapshot(ExperimentConfigDraftApproval approval,
                                                  ApplicationSpace applicationSpace) {
        if (!isApprovalRequired(applicationSpace)) {
            approval.setApprovalOwnersSnapshot(List.of());
            approval.setApprovalRequiredCountSnapshot(1);
            approval.setApprovalPolicyVersion(null);
            return;
        }
        List<String> approvalOwners = resolveApprovalOwners(applicationSpace);
        approval.setApprovalOwnersSnapshot(approvalOwners);
        approval.setApprovalRequiredCountSnapshot(resolveApprovalRequiredCount(applicationSpace, approvalOwners));
        approval.setApprovalPolicyVersion(resolveApprovalPolicyVersion(applicationSpace));
    }

    private Optional<ExperimentConfigDraftApproval> findDraftApproval(String experimentId, Long draftVersion) {
        if (draftVersion == null) {
            return Optional.empty();
        }
        Optional<ExperimentConfigDraftApproval> draftApproval =
                configService.getExperimentConfigDraftApproval(experimentId, draftVersion);
        return draftApproval == null ? Optional.empty() : draftApproval;
    }

    private Optional<ExperimentConfigDraftApproval> findCurrentDraftApproval(String experimentId) {
        Optional<ExperimentConfigDraftApproval> draftApproval =
                configService.getCurrentExperimentConfigDraftApproval(experimentId);
        return draftApproval == null ? Optional.empty() : draftApproval;
    }

    private Optional<ExperimentConfigDraftApproval> updateCurrentDraftApprovalStatus(
            String experimentId, Optional<ExperimentConfigDraftApproval> draftApproval,
            ExperimentMetadata.ApprovalStatus targetStatus, String operator, String comment) {
        if (draftApproval.isEmpty()
                || draftApproval.get().getApprovalStatus() != ExperimentMetadata.ApprovalStatus.PENDING) {
            return Optional.empty();
        }
        return configService.updateExperimentConfigDraftApprovalStatus(experimentId,
                draftApproval.get().getDraftVersion(), targetStatus, operator, comment);
    }

    private ExperimentMetadata.ApprovalStatus resolveCurrentApprovalStatus(
            ExperimentMetadata metadata, ApplicationSpace applicationSpace,
            Optional<ExperimentConfigDraftApproval> draftApproval) {
        if (isCurrentDraftApprovalPending(draftApproval)) {
            return draftApproval.get().getApprovalStatus();
        }
        return resolveApprovalStatus(metadata, applicationSpace);
    }

    private ExperimentApprovalTaskType resolveCurrentApprovalType(Optional<ExperimentConfigDraftApproval> draftApproval) {
        return isCurrentDraftApprovalPending(draftApproval)
                ? ExperimentApprovalTaskType.CONFIG_DRAFT : ExperimentApprovalTaskType.EXPERIMENT_START;
    }

    private boolean isCurrentDraftApprovalPending(Optional<ExperimentConfigDraftApproval> draftApproval) {
        return draftApproval.isPresent()
                && draftApproval.get().getApprovalStatus() == ExperimentMetadata.ApprovalStatus.PENDING;
    }

    private long resolveApprovalVoteDraftVersion(Optional<ExperimentConfigDraftApproval> draftApproval) {
        return isCurrentDraftApprovalPending(draftApproval)
                ? draftApproval.get().getDraftVersion() : EXPERIMENT_START_DRAFT_VERSION;
    }

    private ExperimentApprovalVote saveApprovalVote(String experimentId, ExperimentApprovalTaskType approvalType,
                                                    long draftVersion,
                                                    ExperimentMetadata.ApprovalStatus approvalStatus,
                                                    String operator, String comment) {
        ExperimentApprovalVote vote = new ExperimentApprovalVote();
        vote.setExperimentId(experimentId);
        vote.setApprovalType(approvalType);
        vote.setDraftVersion(draftVersion);
        vote.setApprovalStatus(approvalStatus);
        vote.setApprovalOperator(operator);
        vote.setApprovalComment(comment);
        if (experimentApprovalVoteRepository == null) {
            return vote;
        }
        ExperimentApprovalVote savedVote = experimentApprovalVoteRepository.save(vote);
        return savedVote == null ? vote : savedVote;
    }

    private List<ExperimentApprovalVote> listApprovalVotes(String experimentId, ExperimentApprovalTaskType approvalType,
                                                           long draftVersion,
                                                           ExperimentApprovalVote currentVote) {
        List<ExperimentApprovalVote> approvalVotes = List.of();
        if (experimentApprovalVoteRepository != null) {
            List<ExperimentApprovalVote> storedVotes =
                    experimentApprovalVoteRepository.listByApprovalTask(experimentId, approvalType, draftVersion);
            approvalVotes = storedVotes == null ? List.of() : storedVotes;
        }
        if (currentVote == null) {
            return approvalVotes;
        }
        Map<String, ExperimentApprovalVote> votesByOperator = new LinkedHashMap<>();
        for (ExperimentApprovalVote approvalVote : approvalVotes) {
            String approvalOperator = trimToNull(approvalVote.getApprovalOperator());
            if (approvalOperator != null) {
                votesByOperator.put(approvalOperator, approvalVote);
            }
        }
        votesByOperator.put(currentVote.getApprovalOperator(), currentVote);
        return new ArrayList<>(votesByOperator.values());
    }

    private int countApprovalVotes(List<ExperimentApprovalVote> approvalVotes,
                                   ExperimentMetadata.ApprovalStatus approvalStatus) {
        if (approvalVotes == null || approvalVotes.isEmpty()) {
            return 0;
        }
        return (int) approvalVotes.stream()
                .filter(vote -> vote.getApprovalStatus() == approvalStatus)
                .count();
    }

    private int resolveApprovalRequiredCount(ApplicationSpace applicationSpace, List<String> approvalOwners) {
        int approvalRequiredCount = DEFAULT_APPROVAL_REQUIRED_COUNT;
        if (applicationSpace != null && applicationSpace.getApprovalRequiredCount() != null
                && applicationSpace.getApprovalRequiredCount() >= DEFAULT_APPROVAL_REQUIRED_COUNT) {
            approvalRequiredCount = applicationSpace.getApprovalRequiredCount();
        }
        if (approvalOwners == null || approvalOwners.isEmpty()) {
            return DEFAULT_APPROVAL_REQUIRED_COUNT;
        }
        return Math.min(approvalRequiredCount, approvalOwners.size());
    }

    private int resolveApprovalRequiredCountForTask(ExperimentMetadata metadata, ApplicationSpace applicationSpace,
                                                    Optional<ExperimentConfigDraftApproval> draftApproval,
                                                    List<String> approvalOwners) {
        Integer requiredCountSnapshot = resolveApprovalRequiredCountSnapshot(metadata, draftApproval);
        if (requiredCountSnapshot != null && requiredCountSnapshot >= DEFAULT_APPROVAL_REQUIRED_COUNT) {
            if (approvalOwners == null || approvalOwners.isEmpty()) {
                return DEFAULT_APPROVAL_REQUIRED_COUNT;
            }
            return Math.min(requiredCountSnapshot, approvalOwners.size());
        }
        return resolveApprovalRequiredCount(applicationSpace, approvalOwners);
    }

    private Integer resolveApprovalRequiredCountSnapshot(ExperimentMetadata metadata,
                                                         Optional<ExperimentConfigDraftApproval> draftApproval) {
        if (isCurrentDraftApprovalPending(draftApproval)
                && draftApproval.get().getApprovalRequiredCountSnapshot() != null) {
            return draftApproval.get().getApprovalRequiredCountSnapshot();
        }
        return metadata.getApprovalRequiredCountSnapshot();
    }

    private Long resolveApprovalPolicyVersion(ApplicationSpace applicationSpace) {
        if (applicationSpace == null || applicationSpace.getApprovalPolicyVersion() == null
                || applicationSpace.getApprovalPolicyVersion() < 1L) {
            return 1L;
        }
        return applicationSpace.getApprovalPolicyVersion();
    }

    private ExperimentMetadata.ApprovalStatus resolveAggregatedApprovalStatus(
            ExperimentMetadata.ApprovalStatus targetStatus, int approvalApprovedCount, int approvalRequiredCount) {
        if (targetStatus == ExperimentMetadata.ApprovalStatus.REJECTED) {
            return ExperimentMetadata.ApprovalStatus.REJECTED;
        }
        return approvalApprovedCount >= approvalRequiredCount
                ? ExperimentMetadata.ApprovalStatus.APPROVED : ExperimentMetadata.ApprovalStatus.PENDING;
    }

    private String resolveAggregatedApprovalComment(ExperimentMetadata.ApprovalStatus finalStatus,
                                                    String approvalComment, int approvalApprovedCount,
                                                    int approvalRequiredCount, int approvalRejectedCount) {
        if (finalStatus == ExperimentMetadata.ApprovalStatus.PENDING) {
            return buildApprovalProgressText(approvalApprovedCount, approvalRequiredCount, approvalRejectedCount);
        }
        if (approvalComment != null) {
            return approvalComment;
        }
        if (finalStatus == ExperimentMetadata.ApprovalStatus.REJECTED) {
            return APPROVAL_REJECTED_DEFAULT_COMMENT;
        }
        return buildApprovalProgressText(approvalApprovedCount, approvalRequiredCount, approvalRejectedCount);
    }

    private String buildApprovalProgressText(int approvalApprovedCount, int approvalRequiredCount,
                                             int approvalRejectedCount) {
        String progressText = "审批进度 " + approvalApprovedCount + "/" + approvalRequiredCount;
        if (approvalRejectedCount > 0) {
            return progressText + "，已拒绝 " + approvalRejectedCount;
        }
        return progressText;
    }

    private String resolveAggregatedApprovalOperator(ExperimentMetadata metadata,
                                                     Optional<ExperimentConfigDraftApproval> draftApproval,
                                                     String operator,
                                                     ExperimentMetadata.ApprovalStatus finalStatus) {
        if (finalStatus != ExperimentMetadata.ApprovalStatus.PENDING) {
            return operator;
        }
        String requestedBy = resolveApprovalRequestedBy(metadata, draftApproval);
        return requestedBy == null ? operator : requestedBy;
    }

    private boolean isApprovalRequired(ApplicationSpace applicationSpace) {
        return applicationSpace != null && Boolean.TRUE.equals(applicationSpace.getApprovalRequired());
    }

    private ExperimentMetadata.ApprovalStatus resolveApprovalStatus(ExperimentMetadata metadata,
                                                                    ApplicationSpace applicationSpace) {
        ExperimentMetadata.ApprovalStatus approvalStatus = metadata.getApprovalStatus();
        if (approvalStatus != null) {
            return approvalStatus;
        }
        return isApprovalRequired(applicationSpace)
                ? ExperimentMetadata.ApprovalStatus.PENDING : ExperimentMetadata.ApprovalStatus.NOT_REQUIRED;
    }

    private void validateApprovalOperationPermission(ExperimentMetadata metadata, ApplicationSpace applicationSpace,
                                                     Optional<ExperimentConfigDraftApproval> draftApproval,
                                                     String operator) {
        List<String> approvalOwners = resolveApprovalOwnersForTask(metadata, applicationSpace, draftApproval);
        String requestedBy = resolveApprovalRequestedBy(metadata, draftApproval);
        String disabledReason = resolveApprovalDisabledReason(approvalOwners, requestedBy, operator);
        if (disabledReason != null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, disabledReason);
        }
    }

    private String resolveApprovalRequestedBy(ExperimentMetadata metadata,
                                              Optional<ExperimentConfigDraftApproval> draftApproval) {
        if (draftApproval.isPresent()
                && draftApproval.get().getApprovalStatus() == ExperimentMetadata.ApprovalStatus.PENDING) {
            return trimToNull(draftApproval.get().getRequestedBy());
        }
        return trimToNull(metadata.getApprovalOperator());
    }

    private List<String> resolveApprovalOwnersForTask(ExperimentMetadata metadata,
                                                      ApplicationSpace applicationSpace,
                                                      Optional<ExperimentConfigDraftApproval> draftApproval) {
        if (isCurrentDraftApprovalPending(draftApproval)
                && draftApproval.get().getApprovalOwnersSnapshot() != null
                && !draftApproval.get().getApprovalOwnersSnapshot().isEmpty()) {
            return normalizeApprovalOwners(draftApproval.get().getApprovalOwnersSnapshot());
        }
        if (metadata.getApprovalOwnersSnapshot() != null && !metadata.getApprovalOwnersSnapshot().isEmpty()) {
            return normalizeApprovalOwners(metadata.getApprovalOwnersSnapshot());
        }
        return resolveApprovalOwners(applicationSpace);
    }

    private List<String> resolveApprovalOwners(ApplicationSpace applicationSpace) {
        if (applicationSpace == null) {
            return List.of();
        }
        if (applicationSpace.getApprovalOwners() != null && !applicationSpace.getApprovalOwners().isEmpty()) {
            return applicationSpace.getApprovalOwners().stream()
                    .map(this::trimToNull)
                    .filter(owner -> owner != null)
                    .distinct()
                    .toList();
        }
        String defaultOwner = trimToNull(applicationSpace.getDefaultOwner());
        return defaultOwner == null ? List.of() : List.of(defaultOwner);
    }

    private List<String> normalizeApprovalOwners(List<String> approvalOwners) {
        if (approvalOwners == null || approvalOwners.isEmpty()) {
            return List.of();
        }
        return approvalOwners.stream()
                .map(this::trimToNull)
                .filter(owner -> owner != null)
                .distinct()
                .toList();
    }

    private String resolveApprovalDisabledReason(List<String> approvalOwners, String requestedBy, String operator) {
        if (isCurrentPrincipalAdmin()) {
            return null;
        }
        String normalizedOperator = trimToNull(operator);
        if (approvalOwners == null || approvalOwners.isEmpty()) {
            return "当前应用未配置审批负责人";
        }
        if (!approvalOwners.contains(normalizedOperator)) {
            return "仅应用审批负责人可操作: " + String.join(",", approvalOwners);
        }
        if (requestedBy != null && requestedBy.equals(normalizedOperator)) {
            return "提交人不能审批自己的变更";
        }
        return null;
    }

    private void validateApprovalRiskGate(ExperimentMetadata.ApprovalStatus targetStatus,
                                          ExperimentApprovalStatusUpdateRequest request,
                                          ApprovalRiskContext riskContext) {
        if (targetStatus != ExperimentMetadata.ApprovalStatus.APPROVED) {
            return;
        }
        String disabledReason = resolveApprovalRiskDisabledReason(riskContext);
        if (disabledReason == null) {
            return;
        }
        if (Boolean.TRUE.equals(request.getRiskOverride())) {
            validateApprovalRiskOverride(request);
            return;
        }
        throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, disabledReason);
    }

    private void validateApprovalRiskOverride(ExperimentApprovalStatusUpdateRequest request) {
        if (!isCurrentPrincipalAdmin()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只有 admin 可以豁免审批风险");
        }
        if (trimToNull(request.getRiskOverrideReason()) == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "风险豁免原因不能为空");
        }
    }

    private void applyApprovalRiskAuditDetail(Map<String, Object> detail,
                                              ExperimentMetadata.ApprovalStatus targetStatus,
                                              ExperimentApprovalStatusUpdateRequest request,
                                              ApprovalRiskContext riskContext) {
        if (targetStatus != ExperimentMetadata.ApprovalStatus.APPROVED
                || APPROVAL_RISK_LEVEL_UNKNOWN.equals(riskContext.riskLevel)) {
            return;
        }
        detail.put("approvalRiskLevel", riskContext.riskLevel);
        detail.put("approvalRiskFlags", riskContext.riskFlags);
        detail.put("guardrailStatus", riskContext.guardrailStatus);
        detail.put("analysisReady", riskContext.analysisReady);
        detail.put("hasSrm", riskContext.hasSrm);
        detail.put("breachedGuardrails", riskContext.breachedGuardrails);
        detail.put("latestReportSnapshotVersion", riskContext.latestSnapshotVersion);
        detail.put("riskOverride", Boolean.TRUE.equals(request.getRiskOverride()));
        detail.put("riskOverrideReason", trimToNull(request.getRiskOverrideReason()));
    }

    private ApprovalRiskContext resolveApprovalRiskContext(String experimentId) {
        Optional<ExperimentReportSnapshot> latestSnapshot = findLatestReportSnapshot(experimentId);
        if (latestSnapshot.isEmpty()) {
            ApprovalRiskContext context = new ApprovalRiskContext();
            context.riskLevel = APPROVAL_RISK_LEVEL_UNKNOWN;
            context.riskFlags = List.of();
            context.guardrailStatus = APPROVAL_RISK_LEVEL_UNKNOWN;
            context.breachedGuardrails = List.of();
            return context;
        }
        ExperimentReportSnapshot snapshot = latestSnapshot.get();
        List<String> breachedGuardrails = normalizeBreachedGuardrails(snapshot.getBreachedGuardrails());
        List<String> riskFlags = new ArrayList<>();
        if (Boolean.FALSE.equals(snapshot.getAnalysisReady())) {
            riskFlags.add(APPROVAL_RISK_FLAG_ANALYSIS_NOT_READY);
        }
        if (Boolean.TRUE.equals(snapshot.getHasSrm())) {
            riskFlags.add(APPROVAL_RISK_FLAG_SRM);
        }
        if (!breachedGuardrails.isEmpty()) {
            riskFlags.add(APPROVAL_RISK_FLAG_GUARDRAIL_BREACHED);
        }

        boolean blocked = Boolean.TRUE.equals(snapshot.getHasSrm()) || !breachedGuardrails.isEmpty();
        ApprovalRiskContext context = new ApprovalRiskContext();
        context.latestSnapshotVersion = snapshot.getSnapshotVersion();
        context.latestGeneratedAt = snapshot.getGeneratedAt();
        context.analysisReady = snapshot.getAnalysisReady();
        context.hasSrm = snapshot.getHasSrm();
        context.breachedGuardrails = breachedGuardrails;
        context.riskFlags = riskFlags;
        context.riskLevel = blocked ? APPROVAL_RISK_LEVEL_BLOCKED
                : (riskFlags.isEmpty() ? APPROVAL_RISK_LEVEL_CLEAR : APPROVAL_RISK_LEVEL_WARNING);
        context.guardrailStatus = blocked ? GUARDRAIL_STATUS_BLOCKED : GUARDRAIL_STATUS_PASS;
        return context;
    }

    private Optional<ExperimentReportSnapshot> findLatestReportSnapshot(String experimentId) {
        if (analysisService == null) {
            return Optional.empty();
        }
        try {
            List<ExperimentReportSnapshot> snapshots = analysisService.listReportSnapshots(experimentId);
            return Optional.ofNullable(ExperimentConclusionStatusPolicy.resolveLatestSnapshot(snapshots));
        } catch (Exception exception) {
            log.debug("加载审批风险上下文失败: experimentId={}", experimentId, exception);
            return Optional.empty();
        }
    }

    private List<String> normalizeBreachedGuardrails(List<String> breachedGuardrails) {
        if (breachedGuardrails == null || breachedGuardrails.isEmpty()) {
            return List.of();
        }
        return breachedGuardrails.stream()
                .map(this::trimToNull)
                .filter(guardrail -> guardrail != null)
                .distinct()
                .toList();
    }

    private String resolveApprovalRiskDisabledReason(ApprovalRiskContext riskContext) {
        if (riskContext == null || !APPROVAL_RISK_LEVEL_BLOCKED.equals(riskContext.riskLevel)) {
            return null;
        }
        return "最新报告存在阻断风险，不能通过审批: " + String.join(",", riskContext.riskFlags);
    }

    private boolean isCurrentPrincipalAdmin() {
        return ApiKeyContextHolder.get()
                .map(ApiKeyContextHolder::isAdmin)
                .orElse(false);
    }

    private void validateApprovalStatusTransition(ExperimentMetadata.ApprovalStatus currentStatus,
                                                  ExperimentMetadata.ApprovalStatus targetStatus) {
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "不允许的审批状态流转: " + currentStatus + " -> " + targetStatus
                            + "，允许的下一步: " + currentStatus.allowedTransitions());
        }
    }

    private void validateApplicationSpaceQuota(String appId, ApplicationSpace applicationSpace) {
        if (applicationSpace == null || applicationSpace.getExperimentQuota() == null) {
            return;
        }
        int experimentQuota = applicationSpace.getExperimentQuota();
        if (countExperimentsByAppId(appId) >= experimentQuota) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "应用 " + appId + " 实验配额已满: " + experimentQuota);
        }
    }

    private int countExperimentsByAppId(String appId) {
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            if (experimentIds == null || experimentIds.isEmpty()) {
                return 0;
            }
            int experimentCount = 0;
            for (String experimentId : experimentIds) {
                ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                if (metadata != null && appId.equals(ApiKeyContextHolder.resolveMetadataAppId(metadata))) {
                    experimentCount++;
                }
            }
            return experimentCount;
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "检查应用实验配额失败: " + e.getMessage());
        }
    }

    /**
     * 转换为响应对象
     */
    private ExperimentResponse convertToResponse(ExperimentMetadata metadata) {
        ExperimentResponse response = new ExperimentResponse();
        BeanUtils.copyProperties(metadata.getExperiment(), response);
        response.setConfigVersion(metadata.getConfigVersion());
        response.setLayerId(metadata.getLayerId());

        // 转换实验组
        if (metadata.getGroups() != null) {
            Map<String, ExperimentResponse.GroupResponse> groups = metadata.getGroups().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                ExperimentResponse.GroupResponse gr = new ExperimentResponse.GroupResponse();
                                gr.setId(e.getValue().getId());
                                gr.setName(e.getValue().getName());
                                gr.setTrafficRatio(e.getValue().getTrafficRatio());
                                gr.setConfig(e.getValue().getConfig());
                                return gr;
                            }
                    ));
            response.setGroups(groups);
        }

        // 转换流量配置
        if (metadata.getTraffic() != null) {
            ExperimentResponse.TrafficConfigResponse traffic = new ExperimentResponse.TrafficConfigResponse();
            traffic.setTotalTraffic(metadata.getTraffic().getTotalTraffic());
            traffic.setStrategy(metadata.getTraffic().getStrategy() != null
                    ? metadata.getTraffic().getStrategy().name() : null);
            traffic.setHashKey(metadata.getTraffic().getHashKey());
            traffic.setRuleFallbackStrategy(metadata.getTraffic().getRuleFallbackStrategy() != null
                    ? metadata.getTraffic().getRuleFallbackStrategy().name() : null);

            if (metadata.getTraffic().getAllocation() != null) {
                List<ExperimentResponse.GroupAllocationResponse> allocations =
                        metadata.getTraffic().getAllocation().stream()
                                .map(a -> {
                                    ExperimentResponse.GroupAllocationResponse ar =
                                            new ExperimentResponse.GroupAllocationResponse();
                                    ar.setGroup(a.getGroup());
                                    ar.setRatio(a.getRatio());
                                    return ar;
                                })
                                .collect(Collectors.toList());
                traffic.setAllocation(allocations);
            }

            if (metadata.getTraffic().getRules() != null) {
                List<ExperimentResponse.TrafficRuleResponse> trafficRules = metadata.getTraffic().getRules().stream()
                        .map(rule -> {
                            ExperimentResponse.TrafficRuleResponse responseRule =
                                    new ExperimentResponse.TrafficRuleResponse();
                            responseRule.setName(rule.getName());
                            responseRule.setPriority(rule.getPriority());
                            responseRule.setGroup(rule.getGroup());
                            if (rule.getConditions() != null) {
                                List<ExperimentResponse.RuleConditionResponse> conditions = rule.getConditions().stream()
                                        .map(condition -> {
                                            ExperimentResponse.RuleConditionResponse responseCondition =
                                                    new ExperimentResponse.RuleConditionResponse();
                                            responseCondition.setField(condition.getField());
                                            responseCondition.setOperator(condition.getOperator() != null
                                                    ? condition.getOperator().name() : null);
                                            responseCondition.setValue(condition.getValue());
                                            responseCondition.setValues(condition.getValues());
                                            return responseCondition;
                                        })
                                        .collect(Collectors.toList());
                                responseRule.setConditions(conditions);
                            }
                            return responseRule;
                        })
                        .collect(Collectors.toList());
                traffic.setRules(trafficRules);
            }

            response.setTraffic(traffic);
        }

        response.setWhitelist(metadata.getWhitelist());
        response.setBlacklist(metadata.getBlacklist());
        response.setEventDefinitions(metadata.getEventDefinitions());
        response.setMetricDefinitions(metadata.getMetricDefinitions());
        response.setGroupConfigSchema(metadata.getGroupConfigSchema());
        response.setConclusionStatus(metadata.getConclusionStatus());
        response.setConclusionUpdatedAt(metadata.getConclusionUpdatedAt());
        response.setConclusionConfigVersion(metadata.getConclusionConfigVersion());
        response.setConclusionReportSnapshotVersion(metadata.getConclusionReportSnapshotVersion());
        response.setConclusionOperator(metadata.getConclusionOperator());
        response.setConclusionComment(metadata.getConclusionComment());
        response.setSuggestedConclusionStatus(metadata.getSuggestedConclusionStatus());
        response.setSuggestedConclusionUpdatedAt(metadata.getSuggestedConclusionUpdatedAt());
        response.setApprovalStatus(metadata.getApprovalStatus());
        response.setApprovalOperator(metadata.getApprovalOperator());
        response.setApprovalComment(metadata.getApprovalComment());
        response.setApprovalUpdatedAt(metadata.getApprovalUpdatedAt());

        return response;
    }

    private ExperimentConfigVersionResponse convertToConfigVersionResponse(ExperimentConfigVersion configVersion) {
        ExperimentMetadata metadata = configVersion.getMetadata();
        if (metadata != null) {
            normalizeExperimentOwnership(metadata);
        }
        Experiment experiment = metadata == null ? null : metadata.getExperiment();
        ExperimentConfigVersionResponse response = new ExperimentConfigVersionResponse();
        response.setExperimentId(configVersion.getExperimentId());
        response.setConfigVersion(configVersion.getConfigVersion());
        response.setSourceConfigVersion(configVersion.getSourceConfigVersion());
        response.setSourceType(configVersion.getSourceType());
        response.setPublishedBy(configVersion.getPublishedBy());
        response.setPublishComment(configVersion.getPublishComment());
        response.setPublishedAt(configVersion.getPublishedAt());
        if (metadata == null) {
            return response;
        }
        response.setAppId(ApiKeyContextHolder.resolveMetadataAppId(metadata));
        response.setOwner(ApiKeyContextHolder.resolveMetadataOwner(metadata));
        response.setLayerId(metadata.getLayerId());
        response.setApprovalStatus(metadata.getApprovalStatus());
        response.setGroupCount(metadata.getGroups() == null ? 0 : metadata.getGroups().size());
        response.setEventDefinitionCount(metadata.getEventDefinitions() == null ? 0
                : metadata.getEventDefinitions().size());
        response.setMetricDefinitionCount(metadata.getMetricDefinitions() == null ? 0
                : metadata.getMetricDefinitions().size());
        if (experiment != null) {
            response.setExperimentName(experiment.getName());
            response.setExperimentStatus(experiment.getStatus());
        }
        return response;
    }

    private ExperimentConfigDraftResponse convertToConfigDraftResponse(ExperimentMetadata currentMetadata,
                                                                       ExperimentConfigDraft draft,
                                                                       ExperimentConfigDraftApproval draftApproval) {
        ExperimentConfigDraftResponse response = new ExperimentConfigDraftResponse();
        response.setExperimentId(draft.getExperimentId());
        response.setDraftVersion(draft.getDraftVersion());
        response.setCurrentConfigVersion(currentMetadata.getConfigVersion());
        response.setBaseConfigVersion(draft.getBaseConfigVersion());
        response.setStale(!Long.valueOf(currentMetadata.getConfigVersion()).equals(draft.getBaseConfigVersion()));
        response.setUpdatedBy(draft.getUpdatedBy());
        response.setDraftComment(draft.getDraftComment());
        response.setCreatedAt(draft.getCreatedAt());
        response.setUpdatedAt(draft.getUpdatedAt());
        if (draftApproval != null) {
            response.setApprovalStatus(draftApproval.getApprovalStatus());
            response.setApprovalOperator(draftApproval.getApprovalOperator());
            response.setApprovalComment(draftApproval.getApprovalComment());
            response.setApprovalUpdatedAt(draftApproval.getApprovalUpdatedAt());
        }
        ExperimentMetadata draftMetadata = draft.getMetadata();
        if (draftMetadata != null) {
            normalizeExperimentOwnership(draftMetadata);
            response.setDraftExperiment(convertToResponse(draftMetadata));
        }
        return response;
    }

    private ExperimentConfigDraftApprovalResponse convertToConfigDraftApprovalResponse(
            ExperimentConfigDraftApproval approval) {
        ExperimentConfigDraftApprovalResponse response = new ExperimentConfigDraftApprovalResponse();
        response.setExperimentId(approval.getExperimentId());
        response.setDraftVersion(approval.getDraftVersion());
        response.setBaseConfigVersion(approval.getBaseConfigVersion());
        response.setApprovalStatus(approval.getApprovalStatus());
        response.setRequestedBy(approval.getRequestedBy());
        response.setDraftComment(approval.getDraftComment());
        response.setApprovalOperator(approval.getApprovalOperator());
        response.setApprovalComment(approval.getApprovalComment());
        response.setApprovalUpdatedAt(approval.getApprovalUpdatedAt());
        response.setApprovalOwnersSnapshot(approval.getApprovalOwnersSnapshot());
        response.setApprovalRequiredCountSnapshot(approval.getApprovalRequiredCountSnapshot());
        response.setApprovalPolicyVersion(approval.getApprovalPolicyVersion());
        response.setCreatedAt(approval.getCreatedAt());
        response.setUpdatedAt(approval.getUpdatedAt());
        return response;
    }

    private ExperimentMetadata buildDraftMetadata(ExperimentMetadata currentMetadata,
                                                  ExperimentConfigDraftSaveRequest request,
                                                  List<GroupConfigFieldDefinition> groupConfigSchema) {
        Experiment currentExperiment = currentMetadata.getExperiment();
        DefinitionSelection definitionSelection = resolveApplicationDictionarySelection(
                ApiKeyContextHolder.resolveMetadataAppId(currentMetadata), request);
        Experiment draftExperiment = new Experiment();
        draftExperiment.setId(currentExperiment.getId());
        draftExperiment.setName(request.getName());
        draftExperiment.setDescription(request.getDescription());
        draftExperiment.setStatus(currentExperiment.getStatus());
        draftExperiment.setStartTime(request.getStartTime());
        draftExperiment.setEndTime(request.getEndTime());
        draftExperiment.setCreator(currentExperiment.getCreator());
        draftExperiment.setAppId(currentExperiment.getAppId());
        draftExperiment.setOwner(currentExperiment.getOwner());
        draftExperiment.setCreateTime(currentExperiment.getCreateTime());
        draftExperiment.setUpdateTime(LocalDateTime.now());

        ExperimentMetadata draftMetadata = new ExperimentMetadata();
        draftMetadata.setConfigVersion(currentMetadata.getConfigVersion());
        draftMetadata.setLayerId(trimToNull(request.getLayerId()));
        draftMetadata.setAppId(currentMetadata.getAppId());
        draftMetadata.setOwner(currentMetadata.getOwner());
        draftMetadata.setExperiment(draftExperiment);
        draftMetadata.setGroups(buildExperimentGroups(request, groupConfigSchema));
        draftMetadata.setTraffic(buildTrafficConfig(request.getTraffic()));
        draftMetadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        draftMetadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        draftMetadata.setEventDefinitions(definitionSelection.eventDefinitions());
        draftMetadata.setMetricDefinitions(definitionSelection.metricDefinitions());
        draftMetadata.setGroupConfigSchema(groupConfigSchema);
        draftMetadata.setConclusionStatus(currentMetadata.getConclusionStatus());
        draftMetadata.setConclusionUpdatedAt(currentMetadata.getConclusionUpdatedAt());
        draftMetadata.setApprovalStatus(currentMetadata.getApprovalStatus());
        draftMetadata.setApprovalOperator(currentMetadata.getApprovalOperator());
        draftMetadata.setApprovalComment(currentMetadata.getApprovalComment());
        draftMetadata.setApprovalUpdatedAt(currentMetadata.getApprovalUpdatedAt());
        draftMetadata.setApprovalOwnersSnapshot(currentMetadata.getApprovalOwnersSnapshot());
        draftMetadata.setApprovalRequiredCountSnapshot(currentMetadata.getApprovalRequiredCountSnapshot());
        draftMetadata.setApprovalPolicyVersion(currentMetadata.getApprovalPolicyVersion());
        return draftMetadata;
    }

    private Map<String, com.pisces.common.model.ExperimentGroup> buildExperimentGroups(
            ExperimentCreateRequest request, List<GroupConfigFieldDefinition> groupConfigSchema) {
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new LinkedHashMap<>();
        if (request.getGroups() == null) {
            return groups;
        }
        for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
            com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
            group.setId(groupConfig.getId());
            group.setName(groupConfig.getName());
            group.setTrafficRatio(groupConfig.getTrafficRatio());
            group.setConfig(groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema,
                    groupConfig.getConfig(), groupConfig.getId()));
            groups.put(group.getId(), group);
        }
        return groups;
    }

    private void validateDraftPublishBaseline(ExperimentMetadata currentMetadata, ExperimentConfigDraft draft) {
        if (!Long.valueOf(currentMetadata.getConfigVersion()).equals(draft.getBaseConfigVersion())) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "配置草稿已过期，当前版本: " + currentMetadata.getConfigVersion()
                            + "，草稿基线版本: " + draft.getBaseConfigVersion());
        }
    }

    private void validateDraftMetadata(String experimentId, ExperimentMetadata currentMetadata,
                                       ExperimentMetadata draftMetadata) {
        if (draftMetadata == null || draftMetadata.getExperiment() == null) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "配置草稿快照不完整");
        }
        String draftExperimentId = trimToNull(draftMetadata.getExperiment().getId());
        if (draftExperimentId != null && !experimentId.equals(draftExperimentId)) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED,
                    "配置草稿实验ID不一致: " + draftExperimentId);
        }
        String currentAppId = ApiKeyContextHolder.resolveMetadataAppId(currentMetadata);
        String draftAppId = ApiKeyContextHolder.resolveMetadataAppId(draftMetadata);
        if (!currentAppId.equals(draftAppId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "配置草稿不属于当前实验应用");
        }
    }

    private void applyDraftRuntimeFields(ExperimentMetadata currentMetadata,
                                         ExperimentMetadata draftMetadata,
                                         long afterConfigVersion) {
        Experiment currentExperiment = currentMetadata.getExperiment();
        Experiment draftExperiment = draftMetadata.getExperiment();
        LocalDateTime now = LocalDateTime.now();
        draftMetadata.setConfigVersion(afterConfigVersion);
        draftMetadata.setAppId(currentMetadata.getAppId());
        draftMetadata.setOwner(currentMetadata.getOwner());
        draftMetadata.setApprovalStatus(currentMetadata.getApprovalStatus());
        draftMetadata.setApprovalOperator(currentMetadata.getApprovalOperator());
        draftMetadata.setApprovalComment(currentMetadata.getApprovalComment());
        draftMetadata.setApprovalUpdatedAt(currentMetadata.getApprovalUpdatedAt());
        draftMetadata.setApprovalOwnersSnapshot(currentMetadata.getApprovalOwnersSnapshot());
        draftMetadata.setApprovalRequiredCountSnapshot(currentMetadata.getApprovalRequiredCountSnapshot());
        draftMetadata.setApprovalPolicyVersion(currentMetadata.getApprovalPolicyVersion());
        draftMetadata.setConclusionStatus(currentMetadata.getConclusionStatus());
        draftMetadata.setConclusionUpdatedAt(currentMetadata.getConclusionUpdatedAt());
        draftExperiment.setId(currentExperiment.getId());
        draftExperiment.setStatus(currentExperiment.getStatus());
        draftExperiment.setCreator(currentExperiment.getCreator());
        draftExperiment.setCreateTime(currentExperiment.getCreateTime());
        draftExperiment.setAppId(currentExperiment.getAppId());
        draftExperiment.setOwner(currentExperiment.getOwner());
        draftExperiment.setUpdateTime(now);
    }

    private void validateRollbackRequest(ExperimentConfigRollbackRequest request) {
        if (request == null || request.getTargetConfigVersion() == null || request.getTargetConfigVersion() <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "目标配置版本必须大于0");
        }
    }

    private void validateRollbackMetadata(String experimentId, ExperimentMetadata currentMetadata,
                                          ExperimentMetadata rollbackMetadata, long targetConfigVersion) {
        if (rollbackMetadata == null || rollbackMetadata.getExperiment() == null) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED,
                    "配置版本快照不完整: " + targetConfigVersion);
        }
        String rollbackExperimentId = trimToNull(rollbackMetadata.getExperiment().getId());
        if (rollbackExperimentId != null && !experimentId.equals(rollbackExperimentId)) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED,
                    "配置版本快照实验ID不一致: " + rollbackExperimentId);
        }
        String currentAppId = ApiKeyContextHolder.resolveMetadataAppId(currentMetadata);
        String rollbackAppId = ApiKeyContextHolder.resolveMetadataAppId(rollbackMetadata);
        if (!currentAppId.equals(rollbackAppId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "目标配置版本不属于当前实验应用");
        }
    }

    private void applyRollbackRuntimeFields(ExperimentMetadata currentMetadata,
                                            ExperimentMetadata rollbackMetadata,
                                            long afterConfigVersion) {
        Experiment currentExperiment = currentMetadata.getExperiment();
        Experiment rollbackExperiment = rollbackMetadata.getExperiment();
        LocalDateTime now = LocalDateTime.now();
        rollbackMetadata.setConfigVersion(afterConfigVersion);
        rollbackMetadata.setAppId(currentMetadata.getAppId());
        rollbackMetadata.setOwner(currentMetadata.getOwner());
        rollbackMetadata.setApprovalStatus(currentMetadata.getApprovalStatus());
        rollbackMetadata.setApprovalOperator(currentMetadata.getApprovalOperator());
        rollbackMetadata.setApprovalComment(currentMetadata.getApprovalComment());
        rollbackMetadata.setApprovalUpdatedAt(currentMetadata.getApprovalUpdatedAt());
        rollbackMetadata.setApprovalOwnersSnapshot(currentMetadata.getApprovalOwnersSnapshot());
        rollbackMetadata.setApprovalRequiredCountSnapshot(currentMetadata.getApprovalRequiredCountSnapshot());
        rollbackMetadata.setApprovalPolicyVersion(currentMetadata.getApprovalPolicyVersion());
        rollbackMetadata.setConclusionStatus(currentMetadata.getConclusionStatus());
        rollbackMetadata.setConclusionUpdatedAt(currentMetadata.getConclusionUpdatedAt());
        rollbackExperiment.setId(currentExperiment.getId());
        rollbackExperiment.setStatus(currentExperiment.getStatus());
        rollbackExperiment.setCreator(currentExperiment.getCreator());
        rollbackExperiment.setCreateTime(currentExperiment.getCreateTime());
        rollbackExperiment.setAppId(currentExperiment.getAppId());
        rollbackExperiment.setOwner(currentExperiment.getOwner());
        rollbackExperiment.setUpdateTime(now);
    }

    private void enrichSuggestedConclusion(String experimentId, ExperimentMetadata metadata) {
        try {
            List<ExperimentReportSnapshot> reportSnapshots = analysisService.listReportSnapshots(experimentId);
            metadata.setSuggestedConclusionStatus(ExperimentConclusionStatusPolicy.resolveSuggestedStatus(reportSnapshots));
            metadata.setSuggestedConclusionUpdatedAt(
                    ExperimentConclusionStatusPolicy.resolveSuggestedUpdatedAt(reportSnapshots));
        } catch (Exception e) {
            log.debug("加载实验建议状态失败: experimentId={}", experimentId, e);
        }
    }

    private List<EventDefinition> resolveEventDefinitions(ExperimentCreateRequest request) {
        return normalizeEventDefinitions(request.getEventDefinitions());
    }

    private List<MetricDefinition> resolveMetricDefinitions(ExperimentCreateRequest request) {
        return normalizeMetricDefinitions(request.getMetricDefinitions());
    }

    private DefinitionSelection resolveApplicationDictionarySelection(
            String appId, ExperimentCreateRequest request) {
        List<EventDefinition> requestedEvents = resolveEventDefinitions(request);
        List<MetricDefinition> requestedMetrics = resolveMetricDefinitions(request);
        if (applicationDictionaryService == null) {
            return new DefinitionSelection(requestedEvents, requestedMetrics);
        }

        ApplicationDictionaryResponse dictionary = applicationDictionaryService.getApplicationDictionary(appId);
        if (dictionary == null) {
            return new DefinitionSelection(requestedEvents, requestedMetrics);
        }

        Map<String, ApplicationEventDefinition> dictionaryEvents = new LinkedHashMap<>();
        for (ApplicationEventDefinition definition : safeList(dictionary.getEventDefinitions())) {
            if (definition != null && trimToNull(definition.getKey()) != null) {
                dictionaryEvents.put(definition.getKey().trim().toUpperCase(Locale.ROOT), definition);
            }
        }
        Map<String, ApplicationMetricDefinition> dictionaryMetrics = new LinkedHashMap<>();
        for (ApplicationMetricDefinition definition : safeList(dictionary.getMetricDefinitions())) {
            if (definition != null && trimToNull(definition.getKey()) != null) {
                dictionaryMetrics.put(definition.getKey().trim().toUpperCase(Locale.ROOT), definition);
            }
        }

        List<EventDefinition> selectedEvents = requestedEvents.stream()
                .map(requested -> copySelectedEventDefinition(requested, dictionaryEvents))
                .toList();
        List<MetricDefinition> selectedMetrics = requestedMetrics.stream()
                .map(requested -> copySelectedMetricDefinition(requested, dictionaryMetrics))
                .toList();
        validateSelectedMetricReferences(selectedEvents, selectedMetrics);
        return new DefinitionSelection(selectedEvents, selectedMetrics);
    }

    private EventDefinition copySelectedEventDefinition(
            EventDefinition requested, Map<String, ApplicationEventDefinition> dictionaryEvents) {
        ApplicationEventDefinition source = dictionaryEvents.get(requested.getKey());
        if (source == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "事件不属于所选应用字典：" + requested.getKey());
        }
        EventDefinition selected = new EventDefinition();
        selected.setKey(requested.getKey());
        selected.setLabel(source.getLabel());
        selected.setDescription(source.getDescription());
        selected.setCategory(source.getCategory());
        selected.setPrimary(Boolean.TRUE.equals(source.getPrimary()));
        return selected;
    }

    private MetricDefinition copySelectedMetricDefinition(
            MetricDefinition requested, Map<String, ApplicationMetricDefinition> dictionaryMetrics) {
        ApplicationMetricDefinition source = dictionaryMetrics.get(requested.getKey());
        if (source == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "指标不属于所选应用字典：" + requested.getKey());
        }
        MetricDefinition selected = new MetricDefinition();
        selected.setKey(requested.getKey());
        selected.setName(source.getName());
        selected.setDescription(source.getDescription());
        selected.setAggregationType(source.getAggregationType());
        selected.setNumeratorEventType(source.getNumeratorEventType());
        selected.setDenominatorType(source.getDenominatorType());
        selected.setDenominatorEventType(source.getDenominatorEventType());
        selected.setPrimaryMetric(Boolean.TRUE.equals(requested.getPrimaryMetric()));
        selected.setGuardrailMetric(Boolean.TRUE.equals(requested.getGuardrailMetric()));
        return selected;
    }

    private void validateSelectedMetricReferences(List<EventDefinition> events, List<MetricDefinition> metrics) {
        java.util.Set<String> selectedEventKeys = events.stream()
                .map(EventDefinition::getKey)
                .collect(Collectors.toSet());
        for (MetricDefinition metric : metrics) {
            if (!selectedEventKeys.contains(trimToNull(metric.getNumeratorEventType()))) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标“" + metric.getName() + "”依赖的分子事件未选择：" + metric.getNumeratorEventType());
            }
            if (metric.getAggregationType() == MetricDefinition.AggregationType.RATE
                    && metric.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                    && !selectedEventKeys.contains(trimToNull(metric.getDenominatorEventType()))) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标“" + metric.getName() + "”依赖的分母事件未选择：" + metric.getDenominatorEventType());
            }
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record DefinitionSelection(List<EventDefinition> eventDefinitions,
                                       List<MetricDefinition> metricDefinitions) {
    }

    private TrafficConfig buildTrafficConfig(ExperimentCreateRequest.TrafficConfigRequest trafficRequest) {
        if (trafficRequest == null) {
            return null;
        }

        TrafficConfig trafficConfig = new TrafficConfig();
        trafficConfig.setTotalTraffic(trafficRequest.getTotalTraffic());
        trafficConfig.setStrategy(TrafficConfig.TrafficStrategy.ofOrThrow(trafficRequest.getStrategy()));
        trafficConfig.setHashKey(trafficRequest.getHashKey());
        trafficConfig.setRuleFallbackStrategy(trafficRequest.getRuleFallbackStrategy() != null
                ? TrafficConfig.RuleFallbackStrategy.ofOrThrow(trafficRequest.getRuleFallbackStrategy())
                : null);

        if (trafficRequest.getAllocation() != null) {
            List<TrafficConfig.GroupAllocation> allocations = new ArrayList<>();
            for (ExperimentCreateRequest.GroupAllocationRequest allocationRequest : trafficRequest.getAllocation()) {
                TrafficConfig.GroupAllocation allocation = new TrafficConfig.GroupAllocation();
                allocation.setGroup(allocationRequest.getGroup());
                allocation.setRatio(allocationRequest.getRatio());
                allocations.add(allocation);
            }
            trafficConfig.setAllocation(allocations);
        }

        if (trafficRequest.getRules() != null) {
            List<TrafficConfig.TrafficRule> trafficRules = new ArrayList<>();
            for (ExperimentCreateRequest.TrafficRuleRequest ruleRequest : trafficRequest.getRules()) {
                TrafficConfig.TrafficRule trafficRule = new TrafficConfig.TrafficRule();
                trafficRule.setName(ruleRequest.getName());
                trafficRule.setPriority(ruleRequest.getPriority());
                trafficRule.setGroup(ruleRequest.getGroup());

                if (ruleRequest.getConditions() != null) {
                    List<TrafficConfig.RuleCondition> conditions = new ArrayList<>();
                    for (ExperimentCreateRequest.RuleConditionRequest conditionRequest : ruleRequest.getConditions()) {
                        TrafficConfig.RuleCondition condition = new TrafficConfig.RuleCondition();
                        condition.setField(conditionRequest.getField());
                        condition.setOperator(TrafficConfig.RuleOperator.ofOrThrow(conditionRequest.getOperator()));
                        condition.setValue(conditionRequest.getValue());
                        condition.setValues(conditionRequest.getValues());
                        conditions.add(condition);
                    }
                    trafficRule.setConditions(conditions);
                }
                trafficRules.add(trafficRule);
            }
            trafficConfig.setRules(trafficRules);
        }
        return trafficConfig;
    }

    private void validateConclusionStatusTransition(ExperimentMetadata.ConclusionStatus currentStatus,
                                                    ExperimentMetadata.ConclusionStatus targetStatus) {
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "不允许的结论状态流转: " + currentStatus + " -> " + targetStatus
                            + "，允许的下一步: " + currentStatus.allowedTransitions());
        }
    }

    private void validateNoRunningMutexConflict(String experimentId, ExperimentMetadata metadata) {
        String layerId = trimToNull(metadata.getLayerId());
        if (layerId == null || !isMutexLayer(layerId)) {
            return;
        }

        String appId = ApiKeyContextHolder.resolveMetadataAppId(metadata);
        List<String> experimentIds = listAllExperimentIdsForConflictCheck(experimentId, layerId);
        for (String candidateExperimentId : experimentIds) {
            if (experimentId.equals(candidateExperimentId)) {
                continue;
            }
            ExperimentMetadata candidateMetadata = configService.getExperimentConfig(candidateExperimentId);
            if (isRunningMutexConflict(candidateMetadata, appId, layerId)) {
                throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                        "互斥层 " + layerId + " 已有运行中实验: " + candidateExperimentId
                                + "，请先暂停或停止后再启动当前实验");
            }
        }
    }

    private boolean isMutexLayer(String layerId) {
        ExperimentLayer layer = configService.getLayerConfig(layerId);
        return layer != null && layer.getStrategy() == ExperimentLayer.LayerStrategy.MUTEX;
    }

    private List<String> listAllExperimentIdsForConflictCheck(String experimentId, String layerId) {
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            return experimentIds == null ? List.of() : experimentIds;
        } catch (Exception e) {
            log.warn("检查互斥层运行冲突失败: experimentId={}, layerId={}", experimentId, layerId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "检查实验互斥冲突失败: " + e.getMessage());
        }
    }

    private boolean isRunningMutexConflict(ExperimentMetadata candidateMetadata, String appId, String layerId) {
        if (candidateMetadata == null || candidateMetadata.getExperiment() == null) {
            return false;
        }
        normalizeExperimentOwnership(candidateMetadata);
        Experiment candidateExperiment = candidateMetadata.getExperiment();
        return candidateExperiment.getStatus() == Experiment.ExperimentStatus.RUNNING
                && appId.equals(ApiKeyContextHolder.resolveMetadataAppId(candidateMetadata))
                && layerId.equals(trimToNull(candidateMetadata.getLayerId()));
    }

    private List<EventDefinition> normalizeEventDefinitions(List<EventDefinition> eventDefinitions) {
        List<EventDefinition> normalizedDefinitions = new ArrayList<>();
        for (EventDefinition eventDefinition : eventDefinitions) {
            EventDefinition normalizedDefinition = new EventDefinition();
            normalizedDefinition.setKey(normalizeDefinitionKey(eventDefinition.getKey(), "事件编码"));
            normalizedDefinition.setLabel(requireTrimmedText(eventDefinition.getLabel(),
                    "事件名称不能为空: " + eventDefinition.getKey()));
            normalizedDefinition.setDescription(trimToNull(eventDefinition.getDescription()));
            normalizedDefinition.setCategory(trimToNull(eventDefinition.getCategory()));
            normalizedDefinition.setPrimary(Boolean.TRUE.equals(eventDefinition.getPrimary()));
            normalizedDefinitions.add(normalizedDefinition);
        }
        return normalizedDefinitions;
    }

    private List<MetricDefinition> normalizeMetricDefinitions(List<MetricDefinition> metricDefinitions) {
        List<MetricDefinition> normalizedDefinitions = new ArrayList<>();
        for (MetricDefinition metricDefinition : metricDefinitions) {
            MetricDefinition normalizedMetric = new MetricDefinition();
            normalizedMetric.setKey(normalizeDefinitionKey(metricDefinition.getKey(), "指标编码"));
            normalizedMetric.setName(requireTrimmedText(metricDefinition.getName(),
                    "指标名称不能为空: " + metricDefinition.getKey()));
            normalizedMetric.setDescription(trimToNull(metricDefinition.getDescription()));
            normalizedMetric.setAggregationType(metricDefinition.getAggregationType());
            normalizedMetric.setNumeratorEventType(trimToNull(metricDefinition.getNumeratorEventType()));
            normalizedMetric.setDenominatorType(metricDefinition.getDenominatorType());
            normalizedMetric.setDenominatorEventType(trimToNull(metricDefinition.getDenominatorEventType()));
            normalizedMetric.setPrimaryMetric(Boolean.TRUE.equals(metricDefinition.getPrimaryMetric()));
            normalizedMetric.setGuardrailMetric(Boolean.TRUE.equals(metricDefinition.getGuardrailMetric()));
            normalizedDefinitions.add(normalizedMetric);
        }
        return normalizedDefinitions;
    }

    /**
     * 获取实验列表
     */
    @Override
    public List<Experiment> listExperiments() {
        return listExperiments(null, List.of(), null, null);
    }

    @Override
    public List<Experiment> listExperiments(String status, List<String> statuses, String appId, String owner) {
        List<Experiment.ExperimentStatus> targetStatuses = resolveTargetStatuses(status, statuses);
        String targetAppId = trimToNull(appId);
        String targetOwner = trimToNull(owner);
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();

            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (isVisibleExperimentMetadata(metadata)
                            && matchesExperimentFilters(metadata, targetStatuses, targetAppId, targetOwner)) {
                        Experiment experiment = metadata.getExperiment();
                        experiment.setConclusionStatus(metadata.getConclusionStatus());
                        experiments.add(experiment);
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }

            log.info("查询实验列表: status={}, statuses={}, appId={}, owner={}, count={}",
                    status, statuses, targetAppId, targetOwner, experiments.size());
            return experiments;
        } catch (Exception e) {
            log.error("获取实验列表失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<ExperimentApprovalTaskResponse> listApprovalTasks(String appId, String owner,
                                                                  String approvalStatus) {
        String targetAppId = trimToNull(appId);
        String targetOwner = trimToNull(owner);
        ExperimentMetadata.ApprovalStatus targetApprovalStatus = resolveApprovalTaskStatus(approvalStatus);
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<ExperimentApprovalTaskResponse> approvalTasks = new ArrayList<>();
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (!isVisibleExperimentMetadata(metadata)) {
                        continue;
                    }
                    Optional<ExperimentConfigDraftApproval> draftApproval = findCurrentDraftApproval(experimentId);
                    if (draftApproval.isPresent()
                            && matchesApprovalTaskFilters(metadata, draftApproval.get(), targetAppId, targetOwner,
                            targetApprovalStatus)) {
                        approvalTasks.add(buildApprovalTaskResponse(metadata, draftApproval.get()));
                    } else if ((draftApproval.isEmpty()
                            || draftApproval.get().getApprovalStatus() == ExperimentMetadata.ApprovalStatus.NOT_REQUIRED)
                            && matchesApprovalTaskFilters(metadata, targetAppId, targetOwner, targetApprovalStatus)) {
                        approvalTasks.add(buildApprovalTaskResponse(metadata));
                    }
                } catch (Exception exception) {
                    log.warn("获取实验审批任务失败: {}", experimentId, exception);
                }
            }
            approvalTasks.sort(approvalTaskComparator());
            log.info("查询实验审批任务: appId={}, owner={}, approvalStatus={}, count={}",
                    targetAppId, targetOwner, approvalStatus, approvalTasks.size());
            return approvalTasks;
        } catch (Exception exception) {
            log.error("获取实验审批任务列表失败", exception);
            return new ArrayList<>();
        }
    }

    @Override
    public List<ExperimentApprovalEscalationResponse> scanApprovalEscalations(String appId, String owner) {
        if (experimentApprovalEscalationRepository == null) {
            return List.of();
        }
        List<ExperimentApprovalTaskResponse> approvalTasks =
                listApprovalTasks(appId, owner, ExperimentMetadata.ApprovalStatus.PENDING.name());
        List<ExperimentApprovalEscalationResponse> escalations = new ArrayList<>();
        for (ExperimentApprovalTaskResponse approvalTask : approvalTasks) {
            if (!isApprovalTaskEscalatable(approvalTask)) {
                continue;
            }
            ExperimentApprovalEscalation escalation = findOrCreateApprovalEscalation(approvalTask);
            escalations.add(buildApprovalEscalationResponse(escalation));
        }
        escalations.sort(approvalEscalationComparator());
        return escalations;
    }

    @Override
    public List<ExperimentApprovalEscalationResponse> listApprovalEscalations(String appId, String owner,
                                                                              String escalationStatus) {
        if (experimentApprovalEscalationRepository == null) {
            return List.of();
        }
        String targetAppId = resolveApprovalEscalationAppFilter(appId);
        if (targetAppId != null && targetAppId.isEmpty()) {
            return List.of();
        }
        String targetOwner = trimToNull(owner);
        String targetStatus = resolveApprovalEscalationStatus(escalationStatus);
        List<ExperimentApprovalEscalation> escalations = experimentApprovalEscalationRepository
                .list(targetAppId, targetOwner, targetStatus, DEFAULT_APPROVAL_ESCALATION_QUERY_LIMIT);
        populateApprovalEscalationDeliveries(escalations);
        return escalations.stream()
                .map(this::buildApprovalEscalationResponse)
                .toList();
    }

    @Override
    public ExperimentApprovalEscalationStatusResponse getApprovalEscalationStatus(String appId, String owner) {
        String targetAppId = resolveApprovalEscalationAppFilter(appId);
        String targetOwner = trimToNull(owner);
        if (experimentApprovalEscalationRepository == null
                || (targetAppId != null && targetAppId.isEmpty())) {
            return buildEmptyApprovalEscalationStatusResponse(targetAppId, targetOwner);
        }
        Map<String, Long> escalationStatusCounts = buildApprovalEscalationStatusCounts(
                experimentApprovalEscalationRepository.countByEscalationStatus(targetAppId, targetOwner));
        Map<String, Long> notificationStatusCounts = buildApprovalEscalationStatusCounts(
                experimentApprovalEscalationRepository.countByNotificationStatus(targetAppId, targetOwner));
        Map<String, Long> deliveryStatusCounts = buildApprovalEscalationStatusCounts(
                experimentApprovalEscalationRepository.countDeliveryByNotificationStatus(targetAppId, targetOwner));
        return buildApprovalEscalationStatusResponse(targetAppId, targetOwner, escalationStatusCounts,
                notificationStatusCounts, deliveryStatusCounts);
    }

    @Override
    public ExperimentApprovalEscalationResponse acknowledgeApprovalEscalation(
            String escalationId, ExperimentApprovalEscalationAcknowledgeRequest request) {
        if (experimentApprovalEscalationRepository == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "审批升级告警仓库未启用");
        }
        String normalizedEscalationId = requireTrimmedText(escalationId, "审批升级告警ID不能为空");
        ExperimentApprovalEscalation escalation = experimentApprovalEscalationRepository
                .findByEscalationId(normalizedEscalationId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DATA_NOT_FOUND, "审批升级告警不存在"));
        assertCanAccessApprovalEscalation(escalation);
        if (escalation.getEscalationStatus() != ExperimentApprovalEscalationStatus.OPEN) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR,
                    "只有 OPEN 状态的审批升级告警可以确认");
        }
        String operator = ApiKeyContextHolder.resolveOperator(
                request == null ? AuditLogConstants.OPERATOR_SYSTEM : request.getOperator());
        String comment = trimToNull(request == null ? null : request.getComment());
        int updatedCount = experimentApprovalEscalationRepository.acknowledge(
                normalizedEscalationId, operator, comment, LocalDateTime.now(approvalTaskClock));
        if (updatedCount == 0) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "审批升级告警状态已变化，请刷新后重试");
        }
        return experimentApprovalEscalationRepository.findByEscalationId(normalizedEscalationId)
                .map(this::populateApprovalEscalationDeliveries)
                .map(this::buildApprovalEscalationResponse)
                .orElseThrow(() -> new BusinessException(ResponseCode.DATA_NOT_FOUND, "审批升级告警不存在"));
    }

    @Override
    public ExperimentApprovalEscalationOperationResponse retryApprovalEscalationNotification(
            String escalationId, String operator) {
        if (experimentApprovalEscalationRepository == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "审批升级告警仓库未启用");
        }
        String normalizedEscalationId = requireTrimmedText(escalationId, "审批升级告警ID不能为空");
        ExperimentApprovalEscalation escalation = experimentApprovalEscalationRepository
                .findByEscalationId(normalizedEscalationId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DATA_NOT_FOUND, "审批升级告警不存在"));
        assertCanAccessApprovalEscalation(escalation);
        if (escalation.getEscalationStatus() == ExperimentApprovalEscalationStatus.RESOLVED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "已关闭的审批升级告警不可重投");
        }
        if (escalation.getNotificationStatus() != ExperimentApprovalEscalationNotificationStatus.DEAD) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有 DEAD 投递状态可以手动重投");
        }
        LocalDateTime operatedAt = LocalDateTime.now(approvalTaskClock);
        int affectedCount = experimentApprovalEscalationRepository.retryDeadNotification(
                normalizedEscalationId, operatedAt);
        if (affectedCount == 0) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "审批升级告警状态已变化，请刷新后重试");
        }
        String normalizedOperator = ApiKeyContextHolder.resolveOperator(operator);
        return buildApprovalEscalationOperationResponse(normalizedEscalationId, escalation.getAppId(),
                escalation.getOwner(), normalizedOperator, operatedAt, affectedCount,
                "死信告警已重新进入投递队列");
    }

    @Override
    public ExperimentApprovalEscalationOperationResponse retryDeadApprovalEscalationNotifications(
            String appId, String owner, String operator) {
        if (experimentApprovalEscalationRepository == null) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "审批升级告警仓库未启用");
        }
        String targetAppId = resolveApprovalEscalationAppFilter(appId);
        if (targetAppId != null && targetAppId.isEmpty()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权重投其他应用审批升级告警");
        }
        String targetOwner = trimToNull(owner);
        LocalDateTime operatedAt = LocalDateTime.now(approvalTaskClock);
        int affectedCount = experimentApprovalEscalationRepository.retryDeadNotifications(
                targetAppId, targetOwner, operatedAt);
        String normalizedOperator = ApiKeyContextHolder.resolveOperator(operator);
        return buildApprovalEscalationOperationResponse(null, targetAppId, targetOwner, normalizedOperator,
                operatedAt, affectedCount, "死信告警已批量重新进入投递队列");
    }

    /**
     * 根据状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatus(String status) {
        return listExperiments(status, List.of(), null, null);
    }

    /**
     * 根据多个状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatuses(List<String> statuses) {
        return listExperiments(null, statuses, null, null);
    }

    private List<Experiment.ExperimentStatus> resolveTargetStatuses(String status, List<String> statuses) {
        List<String> statusTexts = new ArrayList<>();
        if (statuses != null && !statuses.isEmpty()) {
            for (String statusText : statuses) {
                String normalizedStatusText = trimToNull(statusText);
                if (normalizedStatusText != null) {
                    statusTexts.add(normalizedStatusText);
                }
            }
        } else {
            String normalizedStatus = trimToNull(status);
            if (normalizedStatus != null) {
                statusTexts.add(normalizedStatus);
            }
        }
        if (statusTexts.isEmpty()) {
            return List.of();
        }
        List<Experiment.ExperimentStatus> targetStatuses = new ArrayList<>();
        for (String statusText : statusTexts) {
            targetStatuses.add(parseExperimentStatus(statusText));
        }
        return targetStatuses;
    }

    private Experiment.ExperimentStatus parseExperimentStatus(String status) {
        try {
            return Experiment.ExperimentStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "不支持的实验状态: " + status + "，支持的状态: DRAFT, RUNNING, PAUSED, STOPPED");
        }
    }

    private ExperimentMetadata.ApprovalStatus resolveApprovalTaskStatus(String approvalStatus) {
        String normalizedStatus = trimToNull(approvalStatus);
        if (normalizedStatus == null) {
            return ExperimentMetadata.ApprovalStatus.PENDING;
        }
        if ("ALL".equalsIgnoreCase(normalizedStatus)) {
            return null;
        }
        try {
            return ExperimentMetadata.ApprovalStatus.ofOrThrow(normalizedStatus);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, exception.getMessage());
        }
    }

    private boolean matchesApprovalTaskFilters(ExperimentMetadata metadata, String targetAppId,
                                               String targetOwner,
                                               ExperimentMetadata.ApprovalStatus targetApprovalStatus) {
        ExperimentMetadata.ApprovalStatus approvalStatus = metadata.getApprovalStatus();
        if (approvalStatus == null || approvalStatus == ExperimentMetadata.ApprovalStatus.NOT_REQUIRED) {
            return false;
        }
        if (targetApprovalStatus != null && approvalStatus != targetApprovalStatus) {
            return false;
        }
        if (targetAppId != null && !targetAppId.equals(ApiKeyContextHolder.resolveMetadataAppId(metadata))) {
            return false;
        }
        return targetOwner == null || targetOwner.equals(ApiKeyContextHolder.resolveMetadataOwner(metadata));
    }

    private boolean matchesApprovalTaskFilters(ExperimentMetadata metadata,
                                               ExperimentConfigDraftApproval draftApproval,
                                               String targetAppId,
                                               String targetOwner,
                                               ExperimentMetadata.ApprovalStatus targetApprovalStatus) {
        ExperimentMetadata.ApprovalStatus approvalStatus = draftApproval.getApprovalStatus();
        if (approvalStatus == null || approvalStatus == ExperimentMetadata.ApprovalStatus.NOT_REQUIRED) {
            return false;
        }
        if (targetApprovalStatus != null && approvalStatus != targetApprovalStatus) {
            return false;
        }
        if (targetAppId != null && !targetAppId.equals(ApiKeyContextHolder.resolveMetadataAppId(metadata))) {
            return false;
        }
        return targetOwner == null || targetOwner.equals(ApiKeyContextHolder.resolveMetadataOwner(metadata));
    }

    private ExperimentApprovalTaskResponse buildApprovalTaskResponse(ExperimentMetadata metadata) {
        Experiment experiment = metadata.getExperiment();
        ExperimentApprovalTaskResponse response = new ExperimentApprovalTaskResponse();
        response.setExperimentId(experiment.getId());
        response.setApprovalType(ExperimentApprovalTaskType.EXPERIMENT_START);
        response.setExperimentName(experiment.getName());
        response.setAppId(ApiKeyContextHolder.resolveMetadataAppId(metadata));
        response.setOwner(ApiKeyContextHolder.resolveMetadataOwner(metadata));
        response.setExperimentStatus(experiment.getStatus());
        response.setApprovalStatus(metadata.getApprovalStatus());
        response.setApprovalOperator(metadata.getApprovalOperator());
        response.setApprovalRequestedBy(trimToNull(metadata.getApprovalOperator()));
        response.setApprovalComment(metadata.getApprovalComment());
        response.setApprovalUpdatedAt(metadata.getApprovalUpdatedAt());
        response.setApprovalSubmittedAt(metadata.getApprovalUpdatedAt());
        response.setConfigVersion(metadata.getConfigVersion());
        response.setLayerId(metadata.getLayerId());
        response.setStartTime(experiment.getStartTime());
        response.setEndTime(experiment.getEndTime());
        response.setUpdateTime(experiment.getUpdateTime());
        populateApprovalTaskPermission(response, metadata, Optional.empty(), response.getApprovalRequestedBy());
        return response;
    }

    private ExperimentApprovalTaskResponse buildApprovalTaskResponse(ExperimentMetadata metadata,
                                                                     ExperimentConfigDraftApproval draftApproval) {
        ExperimentApprovalTaskResponse response = buildApprovalTaskResponse(metadata);
        response.setApprovalType(ExperimentApprovalTaskType.CONFIG_DRAFT);
        response.setApprovalStatus(draftApproval.getApprovalStatus());
        response.setApprovalOperator(draftApproval.getApprovalOperator());
        response.setApprovalRequestedBy(trimToNull(draftApproval.getRequestedBy()));
        response.setApprovalComment(draftApproval.getApprovalComment());
        response.setApprovalUpdatedAt(draftApproval.getApprovalUpdatedAt());
        response.setApprovalSubmittedAt(resolveApprovalSubmittedAt(draftApproval));
        response.setDraftComment(draftApproval.getDraftComment());
        response.setDraftVersion(draftApproval.getDraftVersion());
        response.setBaseConfigVersion(draftApproval.getBaseConfigVersion());
        populateApprovalTaskPermission(response, metadata, Optional.of(draftApproval), response.getApprovalRequestedBy());
        return response;
    }

    private void populateApprovalTaskPermission(ExperimentApprovalTaskResponse response, ExperimentMetadata metadata,
                                                Optional<ExperimentConfigDraftApproval> draftApproval,
                                                String requestedBy) {
        ApplicationSpace applicationSpace =
                findApplicationSpace(ApiKeyContextHolder.resolveMetadataAppId(metadata)).orElse(null);
        List<String> approvalOwners = resolveApprovalOwnersForTask(metadata, applicationSpace, draftApproval);
        String operator = resolveCurrentOperator();
        String disabledReason = resolveApprovalDisabledReason(approvalOwners, requestedBy, operator);
        long draftVersion = response.getDraftVersion() == null ? EXPERIMENT_START_DRAFT_VERSION
                : response.getDraftVersion();
        List<ExperimentApprovalVote> approvalVotes =
                listApprovalVotes(response.getExperimentId(), response.getApprovalType(), draftVersion, null);
        int approvalRequiredCount = resolveApprovalRequiredCountForTask(metadata, applicationSpace, draftApproval,
                approvalOwners);
        int approvalApprovedCount = countApprovalVotes(approvalVotes, ExperimentMetadata.ApprovalStatus.APPROVED);
        int approvalRejectedCount = countApprovalVotes(approvalVotes, ExperimentMetadata.ApprovalStatus.REJECTED);
        ApprovalRiskContext riskContext = resolveApprovalRiskContext(response.getExperimentId());
        String riskDisabledReason = resolveApprovalRiskDisabledReason(riskContext);
        String finalDisabledReason = disabledReason != null ? disabledReason : riskDisabledReason;
        response.setApprovalOwner(approvalOwners.isEmpty() ? null : String.join(",", approvalOwners));
        response.setApprovalOwners(approvalOwners);
        response.setApprovalRequiredCount(approvalRequiredCount);
        response.setApprovalApprovedCount(approvalApprovedCount);
        response.setApprovalRejectedCount(approvalRejectedCount);
        response.setApprovalProgressText(buildApprovalProgressText(approvalApprovedCount, approvalRequiredCount,
                approvalRejectedCount));
        applyApprovalTaskEscalation(response, applicationSpace);
        applyApprovalRiskContext(response, riskContext);
        response.setApprovable(finalDisabledReason == null);
        response.setApprovalDisabledReason(finalDisabledReason);
    }

    private LocalDateTime resolveApprovalSubmittedAt(ExperimentConfigDraftApproval draftApproval) {
        if (draftApproval.getCreatedAt() != null) {
            return draftApproval.getCreatedAt();
        }
        return draftApproval.getApprovalUpdatedAt();
    }

    private void applyApprovalTaskEscalation(ExperimentApprovalTaskResponse response,
                                             ApplicationSpace applicationSpace) {
        Integer approvalSlaHours = normalizeApprovalSlaHours(applicationSpace);
        LocalDateTime submittedAt = response.getApprovalSubmittedAt();
        if (submittedAt == null) {
            submittedAt = response.getApprovalUpdatedAt();
            response.setApprovalSubmittedAt(submittedAt);
        }
        Long elapsedHours = submittedAt == null ? null : Math.max(0L,
                ChronoUnit.HOURS.between(submittedAt, LocalDateTime.now(approvalTaskClock)));
        response.setApprovalElapsedHours(elapsedHours);
        response.setApprovalSlaHours(approvalSlaHours);
        response.setApprovalEscalationOwners(resolveApprovalEscalationOwners(applicationSpace,
                response.getApprovalOwners()));

        if (approvalSlaHours == null || elapsedHours == null
                || response.getApprovalStatus() != ExperimentMetadata.ApprovalStatus.PENDING) {
            response.setApprovalSlaStatus(null);
            response.setApprovalEscalationReason(null);
            return;
        }

        if (elapsedHours >= approvalSlaHours) {
            response.setApprovalSlaStatus(APPROVAL_SLA_STATUS_OVERDUE);
            response.setApprovalEscalationReason("审批已超过 SLA " + approvalSlaHours + " 小时");
            return;
        }

        if ((double) elapsedHours / approvalSlaHours >= APPROVAL_SLA_DUE_SOON_RATIO) {
            response.setApprovalSlaStatus(APPROVAL_SLA_STATUS_DUE_SOON);
            response.setApprovalEscalationReason("审批即将超过 SLA " + approvalSlaHours + " 小时");
            return;
        }

        response.setApprovalSlaStatus(APPROVAL_SLA_STATUS_ON_TRACK);
        response.setApprovalEscalationReason(null);
    }

    private Integer normalizeApprovalSlaHours(ApplicationSpace applicationSpace) {
        if (applicationSpace == null || applicationSpace.getApprovalSlaHours() == null
                || applicationSpace.getApprovalSlaHours() < 1) {
            return null;
        }
        return applicationSpace.getApprovalSlaHours();
    }

    private List<String> resolveApprovalEscalationOwners(ApplicationSpace applicationSpace,
                                                         List<String> fallbackApprovalOwners) {
        if (applicationSpace != null && applicationSpace.getApprovalEscalationOwners() != null
                && !applicationSpace.getApprovalEscalationOwners().isEmpty()) {
            return applicationSpace.getApprovalEscalationOwners().stream()
                    .map(this::trimToNull)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (fallbackApprovalOwners == null || fallbackApprovalOwners.isEmpty()) {
            return List.of();
        }
        return fallbackApprovalOwners.stream()
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private boolean isApprovalTaskEscalatable(ExperimentApprovalTaskResponse approvalTask) {
        return approvalTask != null
                && approvalTask.getApprovalSubmittedAt() != null
                && approvalTask.getApprovalSlaHours() != null
                && APPROVAL_SLA_STATUS_OVERDUE.equals(approvalTask.getApprovalSlaStatus())
                && approvalTask.getApprovalStatus() == ExperimentMetadata.ApprovalStatus.PENDING;
    }

    private ExperimentApprovalEscalation findOrCreateApprovalEscalation(
            ExperimentApprovalTaskResponse approvalTask) {
        long draftVersion = resolveApprovalEscalationDraftVersion(approvalTask);
        Optional<ExperimentApprovalEscalation> existingEscalation =
                experimentApprovalEscalationRepository.findByTask(approvalTask.getExperimentId(),
                        approvalTask.getApprovalType(), draftVersion, approvalTask.getApprovalSubmittedAt());
        if (existingEscalation.isPresent()) {
            return existingEscalation.get();
        }
        ExperimentApprovalEscalation escalation = buildApprovalEscalation(approvalTask, draftVersion);
        return experimentApprovalEscalationRepository.save(escalation);
    }

    private ExperimentApprovalEscalation buildApprovalEscalation(ExperimentApprovalTaskResponse approvalTask,
                                                                 long draftVersion) {
        ExperimentApprovalEscalation escalation = new ExperimentApprovalEscalation();
        escalation.setEscalationId(buildApprovalEscalationId());
        escalation.setExperimentId(approvalTask.getExperimentId());
        escalation.setApprovalType(approvalTask.getApprovalType());
        escalation.setDraftVersion(draftVersion);
        escalation.setAppId(approvalTask.getAppId());
        escalation.setOwner(approvalTask.getOwner());
        escalation.setExperimentName(approvalTask.getExperimentName());
        escalation.setApprovalSubmittedAt(approvalTask.getApprovalSubmittedAt());
        escalation.setApprovalElapsedHours(approvalTask.getApprovalElapsedHours());
        escalation.setApprovalSlaHours(approvalTask.getApprovalSlaHours());
        escalation.setApprovalSlaStatus(approvalTask.getApprovalSlaStatus());
        escalation.setEscalationOwners(approvalTask.getApprovalEscalationOwners());
        escalation.setEscalationReason(approvalTask.getApprovalEscalationReason());
        escalation.setNotificationChannel(APPROVAL_ESCALATION_NOTIFICATION_CHANNEL);
        escalation.setNotificationPayload(buildApprovalEscalationPayload(approvalTask));
        escalation.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.PENDING);
        escalation.setNotificationAttemptCount(0);
        escalation.setEscalationStatus(ExperimentApprovalEscalationStatus.OPEN);
        return escalation;
    }

    private Map<String, Object> buildApprovalEscalationPayload(ExperimentApprovalTaskResponse approvalTask) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageType", "APPROVAL_ESCALATION");
        payload.put("experimentId", approvalTask.getExperimentId());
        payload.put("experimentName", approvalTask.getExperimentName());
        payload.put("approvalType", approvalTask.getApprovalType().name());
        payload.put("draftVersion", resolveApprovalEscalationDraftVersion(approvalTask));
        payload.put("appId", approvalTask.getAppId());
        payload.put("owner", approvalTask.getOwner());
        payload.put("approvalSubmittedAt", approvalTask.getApprovalSubmittedAt());
        payload.put("approvalElapsedHours", approvalTask.getApprovalElapsedHours());
        payload.put("approvalSlaHours", approvalTask.getApprovalSlaHours());
        payload.put("approvalSlaStatus", approvalTask.getApprovalSlaStatus());
        payload.put("escalationOwners", approvalTask.getApprovalEscalationOwners());
        payload.put("escalationReason", approvalTask.getApprovalEscalationReason());
        return payload;
    }

    private String buildApprovalEscalationId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "esc_" + uuid.substring(0, APPROVAL_ESCALATION_ID_LENGTH);
    }

    private long resolveApprovalEscalationDraftVersion(ExperimentApprovalTaskResponse approvalTask) {
        if (approvalTask.getDraftVersion() == null) {
            return EXPERIMENT_START_DRAFT_VERSION;
        }
        return approvalTask.getDraftVersion();
    }

    private String resolveApprovalEscalationAppFilter(String appId) {
        String targetAppId = trimToNull(appId);
        Optional<ApiKeyPrincipal> principalOptional = ApiKeyContextHolder.get();
        if (principalOptional.isEmpty() || isCurrentPrincipalAdmin()) {
            return targetAppId;
        }
        String principalAppId = trimToNull(principalOptional.get().getAppId());
        String normalizedPrincipalAppId = principalAppId == null ? ApiKeyContextHolder.DEFAULT_APP_ID : principalAppId;
        if (targetAppId != null && !targetAppId.equals(normalizedPrincipalAppId)) {
            return "";
        }
        return normalizedPrincipalAppId;
    }

    private String resolveApprovalEscalationStatus(String escalationStatus) {
        String normalizedStatus = trimToNull(escalationStatus);
        if (normalizedStatus == null) {
            return ExperimentApprovalEscalationStatus.OPEN.name();
        }
        if ("ALL".equalsIgnoreCase(normalizedStatus)) {
            return null;
        }
        try {
            return ExperimentApprovalEscalationStatus.ofOrThrow(normalizedStatus).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, exception.getMessage());
        }
    }

    private ExperimentApprovalEscalationStatusResponse buildEmptyApprovalEscalationStatusResponse(
            String appId, String owner) {
        return buildApprovalEscalationStatusResponse(appId, owner, Map.of(), Map.of(), Map.of());
    }

    private ExperimentApprovalEscalationStatusResponse buildApprovalEscalationStatusResponse(
            String appId, String owner, Map<String, Long> escalationStatusCounts,
            Map<String, Long> notificationStatusCounts, Map<String, Long> deliveryStatusCounts) {
        long openCount = getApprovalEscalationStatusCount(escalationStatusCounts,
                ExperimentApprovalEscalationStatus.OPEN.name());
        long acknowledgedCount = getApprovalEscalationStatusCount(escalationStatusCounts,
                ExperimentApprovalEscalationStatus.ACKNOWLEDGED.name());
        long resolvedCount = getApprovalEscalationStatusCount(escalationStatusCounts,
                ExperimentApprovalEscalationStatus.RESOLVED.name());
        long pendingCount = getApprovalEscalationStatusCount(notificationStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.PENDING.name());
        long dispatchingCount = getApprovalEscalationStatusCount(notificationStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.DISPATCHING.name());
        long sentCount = getApprovalEscalationStatusCount(notificationStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.SENT.name());
        long retryCount = getApprovalEscalationStatusCount(notificationStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.RETRY.name());
        long deadCount = getApprovalEscalationStatusCount(notificationStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.DEAD.name());
        long deliveryPendingCount = getApprovalEscalationStatusCount(deliveryStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.PENDING.name());
        long deliveryDispatchingCount = getApprovalEscalationStatusCount(deliveryStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.DISPATCHING.name());
        long deliverySentCount = getApprovalEscalationStatusCount(deliveryStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.SENT.name());
        long deliveryRetryCount = getApprovalEscalationStatusCount(deliveryStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.RETRY.name());
        long deliveryDeadCount = getApprovalEscalationStatusCount(deliveryStatusCounts,
                ExperimentApprovalEscalationNotificationStatus.DEAD.name());
        long totalCount = escalationStatusCounts.values().stream().mapToLong(Long::longValue).sum();
        long undeliveredCount = pendingCount + dispatchingCount + retryCount + deadCount;
        long deliveryUndeliveredCount = deliveryPendingCount + deliveryDispatchingCount
                + deliveryRetryCount + deliveryDeadCount;
        ExperimentApprovalEscalationStatusResponse response = new ExperimentApprovalEscalationStatusResponse();
        response.setAppId(appId);
        response.setOwner(owner);
        response.setTotalCount(totalCount);
        response.setOpenCount(openCount);
        response.setAcknowledgedCount(acknowledgedCount);
        response.setResolvedCount(resolvedCount);
        response.setPendingCount(pendingCount);
        response.setDispatchingCount(dispatchingCount);
        response.setSentCount(sentCount);
        response.setRetryCount(retryCount);
        response.setDeadCount(deadCount);
        response.setUndeliveredCount(undeliveredCount);
        response.setDeliveryPendingCount(deliveryPendingCount);
        response.setDeliveryDispatchingCount(deliveryDispatchingCount);
        response.setDeliverySentCount(deliverySentCount);
        response.setDeliveryRetryCount(deliveryRetryCount);
        response.setDeliveryDeadCount(deliveryDeadCount);
        response.setDeliveryUndeliveredCount(deliveryUndeliveredCount);
        response.setHealthy(deadCount == 0L && retryCount == 0L
                && deliveryDeadCount == 0L && deliveryRetryCount == 0L);
        response.setStatus(resolveApprovalEscalationDeliveryStatus(totalCount, pendingCount, dispatchingCount,
                retryCount, deadCount));
        populateApprovalEscalationDispatcherStatus(response);
        response.setGeneratedAt(LocalDateTime.now(approvalTaskClock));
        return response;
    }

    private void populateApprovalEscalationDispatcherStatus(ExperimentApprovalEscalationStatusResponse response) {
        if (approvalEscalationNotificationDispatcher == null) {
            response.setDispatcherEnabled(false);
            response.setDispatcherTargetCount(0);
            response.setDispatcherChannels(List.of());
            return;
        }
        List<String> channelNames = approvalEscalationNotificationDispatcher.channelNames();
        response.setDispatcherEnabled(approvalEscalationNotificationDispatcher.isEnabled());
        response.setDispatcherTargetCount(approvalEscalationNotificationDispatcher.targetCount());
        response.setDispatcherChannels(channelNames == null ? List.of() : channelNames);
    }

    private Map<String, Long> buildApprovalEscalationStatusCounts(
            List<ExperimentApprovalEscalationStatusCountEntity> countEntities) {
        Map<String, Long> statusCounts = new HashMap<>();
        if (countEntities == null) {
            return statusCounts;
        }
        for (ExperimentApprovalEscalationStatusCountEntity countEntity : countEntities) {
            if (countEntity == null || trimToNull(countEntity.getStatus()) == null) {
                continue;
            }
            long count = countEntity.getEscalationCount() == null ? 0L : countEntity.getEscalationCount();
            statusCounts.put(countEntity.getStatus(), count);
        }
        return statusCounts;
    }

    private long getApprovalEscalationStatusCount(Map<String, Long> statusCounts, String status) {
        return statusCounts.getOrDefault(status, 0L);
    }

    private String resolveApprovalEscalationDeliveryStatus(long totalCount, long pendingCount, long dispatchingCount,
                                                           long retryCount, long deadCount) {
        if (totalCount == 0L) {
            return APPROVAL_ESCALATION_STATUS_NO_DATA;
        }
        if (deadCount > 0L) {
            return APPROVAL_ESCALATION_STATUS_DEAD;
        }
        if (retryCount > 0L) {
            return APPROVAL_ESCALATION_STATUS_RETRY;
        }
        if (pendingCount + dispatchingCount > 0L) {
            return APPROVAL_ESCALATION_STATUS_PENDING;
        }
        return APPROVAL_ESCALATION_STATUS_SENT;
    }

    private void assertCanAccessApprovalEscalation(ExperimentApprovalEscalation escalation) {
        Optional<ApiKeyPrincipal> principalOptional = ApiKeyContextHolder.get();
        if (principalOptional.isEmpty() || isCurrentPrincipalAdmin()) {
            return;
        }
        String principalAppId = trimToNull(principalOptional.get().getAppId());
        String normalizedPrincipalAppId = principalAppId == null ? ApiKeyContextHolder.DEFAULT_APP_ID : principalAppId;
        if (!normalizedPrincipalAppId.equals(escalation.getAppId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问当前应用审批升级告警");
        }
    }

    private void populateApprovalEscalationDeliveries(List<ExperimentApprovalEscalation> escalations) {
        if (experimentApprovalEscalationRepository == null || escalations == null || escalations.isEmpty()) {
            return;
        }
        List<String> escalationIds = escalations.stream()
                .map(ExperimentApprovalEscalation::getEscalationId)
                .filter(Objects::nonNull)
                .toList();
        List<ExperimentApprovalEscalationDelivery> deliveries =
                experimentApprovalEscalationRepository.listDeliveries(escalationIds);
        Map<String, List<ExperimentApprovalEscalationDelivery>> deliveriesByEscalationId =
                (deliveries == null ? List.<ExperimentApprovalEscalationDelivery>of() : deliveries).stream()
                        .collect(Collectors.groupingBy(ExperimentApprovalEscalationDelivery::getEscalationId));
        for (ExperimentApprovalEscalation escalation : escalations) {
            List<ExperimentApprovalEscalationDelivery> escalationDeliveries = deliveriesByEscalationId.getOrDefault(
                    escalation.getEscalationId(), List.of());
            escalation.setNotificationDeliveries(escalationDeliveries);
        }
    }

    private ExperimentApprovalEscalation populateApprovalEscalationDeliveries(
            ExperimentApprovalEscalation escalation) {
        if (experimentApprovalEscalationRepository == null || escalation == null) {
            return escalation;
        }
        List<ExperimentApprovalEscalationDelivery> deliveries =
                experimentApprovalEscalationRepository.listDeliveries(escalation.getEscalationId());
        escalation.setNotificationDeliveries(deliveries == null ? List.of() : deliveries);
        return escalation;
    }

    private ExperimentApprovalEscalationResponse buildApprovalEscalationResponse(
            ExperimentApprovalEscalation escalation) {
        ExperimentApprovalEscalationResponse response = new ExperimentApprovalEscalationResponse();
        response.setEscalationId(escalation.getEscalationId());
        response.setExperimentId(escalation.getExperimentId());
        response.setApprovalType(escalation.getApprovalType());
        response.setDraftVersion(escalation.getDraftVersion());
        response.setAppId(escalation.getAppId());
        response.setOwner(escalation.getOwner());
        response.setExperimentName(escalation.getExperimentName());
        response.setApprovalSubmittedAt(escalation.getApprovalSubmittedAt());
        response.setApprovalElapsedHours(escalation.getApprovalElapsedHours());
        response.setApprovalSlaHours(escalation.getApprovalSlaHours());
        response.setApprovalSlaStatus(escalation.getApprovalSlaStatus());
        response.setEscalationOwners(escalation.getEscalationOwners());
        response.setEscalationReason(escalation.getEscalationReason());
        response.setNotificationChannel(escalation.getNotificationChannel());
        response.setNotificationPayload(escalation.getNotificationPayload());
        response.setNotificationStatus(escalation.getNotificationStatus());
        response.setNotificationDeliveries(buildApprovalEscalationDeliveryResponses(
                escalation.getNotificationDeliveries()));
        response.setNotificationAttemptCount(escalation.getNotificationAttemptCount());
        response.setNotificationLastAttemptAt(escalation.getNotificationLastAttemptAt());
        response.setNotificationNextAttemptAt(escalation.getNotificationNextAttemptAt());
        response.setNotificationDeliveredAt(escalation.getNotificationDeliveredAt());
        response.setNotificationLastError(escalation.getNotificationLastError());
        response.setEscalationStatus(escalation.getEscalationStatus());
        response.setAcknowledgedBy(escalation.getAcknowledgedBy());
        response.setAcknowledgedComment(escalation.getAcknowledgedComment());
        response.setAcknowledgedAt(escalation.getAcknowledgedAt());
        response.setResolvedBy(escalation.getResolvedBy());
        response.setResolvedReason(escalation.getResolvedReason());
        response.setResolvedAt(escalation.getResolvedAt());
        response.setCreatedAt(escalation.getCreatedAt());
        response.setUpdatedAt(escalation.getUpdatedAt());
        return response;
    }

    private List<ExperimentApprovalEscalationDeliveryResponse> buildApprovalEscalationDeliveryResponses(
            List<ExperimentApprovalEscalationDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return List.of();
        }
        return deliveries.stream()
                .map(this::buildApprovalEscalationDeliveryResponse)
                .toList();
    }

    private ExperimentApprovalEscalationDeliveryResponse buildApprovalEscalationDeliveryResponse(
            ExperimentApprovalEscalationDelivery delivery) {
        ExperimentApprovalEscalationDeliveryResponse response = new ExperimentApprovalEscalationDeliveryResponse();
        response.setEscalationId(delivery.getEscalationId());
        response.setChannelName(delivery.getChannelName());
        response.setTargetKey(delivery.getTargetKey());
        response.setNotificationStatus(delivery.getNotificationStatus());
        response.setNotificationAttemptCount(delivery.getNotificationAttemptCount());
        response.setNotificationLastAttemptAt(delivery.getNotificationLastAttemptAt());
        response.setNotificationNextAttemptAt(delivery.getNotificationNextAttemptAt());
        response.setNotificationDeliveredAt(delivery.getNotificationDeliveredAt());
        response.setNotificationLastError(delivery.getNotificationLastError());
        response.setActive(delivery.getActive());
        response.setCreatedAt(delivery.getCreatedAt());
        response.setUpdatedAt(delivery.getUpdatedAt());
        return response;
    }

    private ExperimentApprovalEscalationOperationResponse buildApprovalEscalationOperationResponse(
            String escalationId, String appId, String owner, String operator, LocalDateTime operatedAt,
            long affectedCount, String message) {
        ExperimentApprovalEscalationOperationResponse response = new ExperimentApprovalEscalationOperationResponse();
        response.setEscalationId(escalationId);
        response.setAppId(appId);
        response.setOwner(owner);
        response.setOperation(APPROVAL_ESCALATION_OPERATION_RETRY_DEAD);
        response.setOperator(operator);
        response.setStatus(APPROVAL_ESCALATION_OPERATION_SUCCESS);
        response.setAffectedCount(affectedCount);
        response.setMessage(message);
        response.setOperatedAt(operatedAt);
        return response;
    }

    private Comparator<ExperimentApprovalEscalationResponse> approvalEscalationComparator() {
        return (left, right) -> {
            LocalDateTime leftCreatedAt = left.getCreatedAt();
            LocalDateTime rightCreatedAt = right.getCreatedAt();
            if (leftCreatedAt == null && rightCreatedAt == null) {
                return left.getEscalationId().compareTo(right.getEscalationId());
            }
            if (leftCreatedAt == null) {
                return 1;
            }
            if (rightCreatedAt == null) {
                return -1;
            }
            return rightCreatedAt.compareTo(leftCreatedAt);
        };
    }

    private void resolveApprovalEscalationsForTask(String experimentId, ExperimentApprovalTaskType approvalType,
                                                   long draftVersion, String operator, String reason) {
        if (experimentApprovalEscalationRepository == null) {
            return;
        }
        experimentApprovalEscalationRepository.resolveByTask(experimentId, approvalType, draftVersion,
                operator, reason, LocalDateTime.now(approvalTaskClock));
    }

    private void applyApprovalRiskContext(ExperimentApprovalTaskResponse response, ApprovalRiskContext riskContext) {
        response.setApprovalRiskLevel(riskContext.riskLevel);
        response.setApprovalRiskFlags(riskContext.riskFlags);
        response.setGuardrailStatus(riskContext.guardrailStatus);
        response.setAnalysisReady(riskContext.analysisReady);
        response.setHasSrm(riskContext.hasSrm);
        response.setBreachedGuardrails(riskContext.breachedGuardrails);
        response.setLatestReportSnapshotVersion(riskContext.latestSnapshotVersion);
        response.setLatestReportGeneratedAt(riskContext.latestGeneratedAt);
        String riskDisabledReason = resolveApprovalRiskDisabledReason(riskContext);
        response.setApprovalRiskDisabledReason(riskDisabledReason);
        response.setRiskOverrideRequired(riskDisabledReason != null);
        response.setRiskOverrideAllowed(riskDisabledReason != null && isCurrentPrincipalAdmin());
    }

    private Comparator<ExperimentApprovalTaskResponse> approvalTaskComparator() {
        return (left, right) -> {
            LocalDateTime leftUpdatedAt = left.getApprovalUpdatedAt();
            LocalDateTime rightUpdatedAt = right.getApprovalUpdatedAt();
            if (leftUpdatedAt == null && rightUpdatedAt == null) {
                return left.getExperimentId().compareTo(right.getExperimentId());
            }
            if (leftUpdatedAt == null) {
                return 1;
            }
            if (rightUpdatedAt == null) {
                return -1;
            }
            return rightUpdatedAt.compareTo(leftUpdatedAt);
        };
    }

    private boolean matchesExperimentFilters(ExperimentMetadata metadata,
                                             List<Experiment.ExperimentStatus> targetStatuses,
                                             String targetAppId,
                                             String targetOwner) {
        Experiment experiment = metadata.getExperiment();
        if (!targetStatuses.isEmpty() && !targetStatuses.contains(experiment.getStatus())) {
            return false;
        }
        if (targetAppId != null && !targetAppId.equals(ApiKeyContextHolder.resolveMetadataAppId(metadata))) {
            return false;
        }
        return targetOwner == null || targetOwner.equals(ApiKeyContextHolder.resolveMetadataOwner(metadata));
    }

    /**
     * 删除实验（无用户系统版本）
     */
    @Override
    public void deleteExperiment(String experimentId) {
        ExperimentMetadata metadata = getExperimentMetadataOrThrow(experimentId);

        try {
            configService.deleteExperimentConfig(experimentId);
        } catch (Exception e) {
            log.error("删除实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "删除实验配置失败: " + e.getMessage());
        }

        log.info("删除实验: {}", experimentId);
        recordExperimentAudit(experimentId, AuditLogConstants.ACTION_EXPERIMENT_DELETE,
                resolveCurrentOperator(), statusName(metadata.getExperiment().getStatus()),
                AuditLogConstants.STATUS_DELETED, "删除实验", buildExperimentAuditDetail(metadata));
    }

    /**
     * 批量暂停实验
     */
    @Override
    public Map<String, Object> batchPauseExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "pause", experimentId -> executeBatchOperation(experimentId, "暂停", this::pauseExperiment));
    }

    /**
     * 批量停止实验
     */
    @Override
    public Map<String, Object> batchStopExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "stop", experimentId -> executeBatchOperation(experimentId, "停止", this::stopExperiment));
    }

    /**
     * 批量恢复实验
     */
    @Override
    public Map<String, Object> batchResumeExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "resume", experimentId -> executeBatchOperation(experimentId, "恢复", this::resumeExperiment));
    }

    /**
     * 批量删除实验
     */
    @Override
    public Map<String, Object> batchDeleteExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "delete", experimentId -> executeBatchOperation(experimentId, "删除", this::deleteExperiment));
    }

    /**
     * 批量操作通用方法
     */
    private Map<String, Object> batchOperation(List<String> experimentIds, String operationName,
                                                java.util.function.Function<String, String> operation) {
        Map<String, Object> result = new HashMap<>();
        List<String> successIds = new ArrayList<>();
        List<Map<String, String>> failedItems = new ArrayList<>();

        if (experimentIds == null || experimentIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "实验ID列表不能为空");
            return result;
        }

        for (String experimentId : experimentIds) {
            String error = operation.apply(experimentId);
            if (error == null) {
                successIds.add(experimentId);
            } else {
                Map<String, String> failedItem = new HashMap<>();
                failedItem.put("id", experimentId);
                failedItem.put("error", error);
                failedItems.add(failedItem);
            }
        }

        result.put("success", failedItems.isEmpty());
        result.put("operation", operationName);
        result.put("total", experimentIds.size());
        result.put("successCount", successIds.size());
        result.put("failedCount", failedItems.size());
        result.put("successIds", successIds);
        result.put("failedItems", failedItems);

        if (failedItems.isEmpty()) {
            result.put("message", String.format("批量%s成功，共%d个实验",
                    getOperationLabel(operationName), successIds.size()));
        } else if (successIds.isEmpty()) {
            result.put("message", String.format("批量%s失败，全部%d个实验操作失败",
                    getOperationLabel(operationName), failedItems.size()));
        } else {
            result.put("message", String.format("批量%s部分成功：%d个成功，%d个失败",
                    getOperationLabel(operationName), successIds.size(), failedItems.size()));
        }

        log.info("批量{}实验: 总数={}, 成功={}, 失败={}",
                operationName, experimentIds.size(), successIds.size(), failedItems.size());

        return result;
    }

    private String getOperationLabel(String operation) {
        switch (operation) {
            case "pause": return "暂停";
            case "stop": return "停止";
            case "resume": return "恢复";
            case "delete": return "删除";
            default: return operation;
        }
    }

    private String executeBatchOperation(String experimentId,
                                         String operationLabel,
                                         java.util.function.Consumer<String> operation) {
        try {
            operation.accept(experimentId);
            return null;
        } catch (BusinessException exception) {
            return exception.getMessage();
        } catch (Exception exception) {
            return operationLabel + "失败: " + exception.getMessage();
        }
    }

    private void recordExperimentAudit(String experimentId,
                                       String action,
                                       String operator,
                                       String beforeStatus,
                                       String afterStatus,
                                       String summary,
                                       Map<String, Object> detail) {
        if (auditLogService == null) {
            return;
        }
        try {
            AuditLogRecord record = new AuditLogRecord();
            record.setResourceType(AuditLogConstants.RESOURCE_TYPE_EXPERIMENT);
            record.setResourceId(experimentId);
            record.setAction(action);
            record.setOperator(operator);
            record.setBeforeStatus(beforeStatus);
            record.setAfterStatus(afterStatus);
            record.setSummary(summary);
            record.setDetail(detail);
            auditLogService.record(record);
        } catch (Exception exception) {
            log.warn("记录实验审计日志失败: experimentId={}, action={}", experimentId, action, exception);
        }
    }

    private Map<String, Object> buildExperimentAuditDetail(ExperimentMetadata metadata) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (metadata == null) {
            return detail;
        }
        Experiment experiment = metadata.getExperiment();
        if (experiment != null) {
            detail.put("experimentName", experiment.getName());
            detail.put("creator", experiment.getCreator());
            detail.put("appId", experiment.getAppId());
            detail.put("owner", experiment.getOwner());
        }
        detail.put("configVersion", metadata.getConfigVersion());
        detail.put("layerId", metadata.getLayerId());
        detail.put("approvalStatus", approvalStatusName(metadata.getApprovalStatus()));
        detail.put("groupCount", metadata.getGroups() == null ? 0 : metadata.getGroups().size());
        return detail;
    }

    private String statusName(Experiment.ExperimentStatus status) {
        return status == null ? null : status.name();
    }

    private String conclusionStatusName(ExperimentMetadata.ConclusionStatus status) {
        return status == null ? null : status.name();
    }

    private String approvalStatusName(ExperimentMetadata.ApprovalStatus status) {
        return status == null ? null : status.name();
    }

    /**
     * 校验实验创建请求参数
     */
    private void validateExperimentRequest(ExperimentCreateRequest request,
                                           List<GroupConfigFieldDefinition> groupConfigSchema) {
        // 校验实验名称
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称不能为空");
        }

        if (request.getName().length() > 100) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称长度不能超过100个字符");
        }

        // 校验实验组
        if (request.getGroups() == null || request.getGroups().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置一个实验组");
        }

        if (request.getGroups().size() < 2) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置2个实验组（基准组和变体组）");
        }

        // 校验实验组ID唯一性
        java.util.Set<String> groupIds = new java.util.HashSet<>();
        for (ExperimentCreateRequest.GroupConfig group : request.getGroups()) {
            if (group.getId() == null || group.getId().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID不能为空");
            }
            if (!groupIds.add(group.getId())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID重复: " + group.getId());
            }
            if (group.getName() == null || group.getName().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组名称不能为空");
            }
            groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema, group.getConfig(), group.getId());
        }

        // 校验流量配置
        if (request.getTraffic() != null) {
            ExperimentCreateRequest.TrafficConfigRequest traffic = request.getTraffic();

            // 校验总流量
            if (traffic.getTotalTraffic() != null) {
                if (traffic.getTotalTraffic() < 0 || traffic.getTotalTraffic() > 1) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, "总流量比例必须在0-1之间");
                }
            }

            // 校验流量分配策略
            if (traffic.getStrategy() != null) {
                try {
                    TrafficConfig.TrafficStrategy.ofOrThrow(traffic.getStrategy());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "不支持的流量分配策略: " + traffic.getStrategy() +
                            "，支持的策略: RANDOM, HASH, RULE, THOMPSON_SAMPLING, UCB");
                }
            }

            // 校验流量分配比例
            if (traffic.getAllocation() != null && !traffic.getAllocation().isEmpty()) {
                double totalRatio = 0.0;
                for (ExperimentCreateRequest.GroupAllocationRequest allocation : traffic.getAllocation()) {
                    if (allocation.getGroup() == null || allocation.getGroup().trim().isEmpty()) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "流量分配的组ID不能为空");
                    }
                    if (!groupIds.contains(allocation.getGroup())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                                "流量分配的组ID不存在: " + allocation.getGroup());
                    }
                    if (allocation.getRatio() == null || allocation.getRatio() < 0 || allocation.getRatio() > 1) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                                "流量分配比例必须在0-1之间: " + allocation.getGroup());
                    }
                    totalRatio += allocation.getRatio();
                }

                // 校验流量分配比例总和（允许小误差）
                if (Math.abs(totalRatio - 1.0) > 0.001) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            String.format("流量分配比例总和必须等于1，当前为%.4f", totalRatio));
                }
            }

            if (traffic.getRuleFallbackStrategy() != null) {
                try {
                    TrafficConfig.RuleFallbackStrategy.ofOrThrow(traffic.getRuleFallbackStrategy());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "不支持的规则回退策略: " + traffic.getRuleFallbackStrategy()
                                    + "，支持的策略: HASH, FIRST_ALLOCATION");
                }
            }

            TrafficConfig trafficConfig = buildTrafficConfig(traffic);
            trafficRuleEvaluator.validateRules(trafficConfig, groupIds);
        }

        // 校验时间范围
        if (request.getStartTime() != null && request.getEndTime() != null) {
            if (request.getEndTime().isBefore(request.getStartTime())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "结束时间不能早于开始时间");
            }
        }

        validateEventDefinitions(request.getEventDefinitions());
        validateMetricDefinitions(request.getMetricDefinitions(), request.getEventDefinitions());
    }

    private void validateEventDefinitions(List<EventDefinition> eventDefinitions) {
        if (eventDefinitions == null || eventDefinitions.isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要定义一个事件");
        }

        java.util.Set<String> eventKeys = new java.util.HashSet<>();
        int primaryEventCount = 0;
        for (EventDefinition eventDefinition : eventDefinitions) {
            if (eventDefinition == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件定义不能为空");
            }
            String eventKey = normalizeDefinitionKey(eventDefinition.getKey(), "事件编码");
            if (!eventKeys.add(eventKey)) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件编码重复: " + eventKey);
            }
            if (eventDefinition.getLabel() == null || eventDefinition.getLabel().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件名称不能为空: " + eventKey);
            }
            if (Boolean.TRUE.equals(eventDefinition.getPrimary())) {
                primaryEventCount++;
            }
        }

        if (primaryEventCount > 1) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "只能配置一个主事件");
        }
    }

    private void validateMetricDefinitions(List<MetricDefinition> metricDefinitions,
                                           List<EventDefinition> eventDefinitions) {
        if (metricDefinitions == null || metricDefinitions.isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要定义一个指标");
        }

        java.util.Set<String> definedEventKeys = eventDefinitions.stream()
                .map(EventDefinition::getKey)
                .map(this::normalizeDefinitionKey)
                .collect(Collectors.toSet());

        java.util.Set<String> metricKeys = new java.util.HashSet<>();
        int primaryMetricCount = 0;
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (metricDefinition == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标定义不能为空");
            }
            String metricKey = normalizeDefinitionKey(metricDefinition.getKey(), "指标编码");
            if (!metricKeys.add(metricKey)) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标编码重复: " + metricKey);
            }
            if (metricDefinition.getName() == null || metricDefinition.getName().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标名称不能为空: " + metricKey);
            }
            if (metricDefinition.getAggregationType() == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标聚合类型不能为空: " + metricKey);
            }
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE) {
                if (metricDefinition.getDenominatorType() == null) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "RATE 指标必须配置分母类型: " + metricKey);
                }
                if (metricDefinition.getNumeratorEventType() == null
                        || metricDefinition.getNumeratorEventType().trim().isEmpty()) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "RATE 指标必须配置分子事件: " + metricKey);
                }
                validateMetricEventReference(metricDefinition.getNumeratorEventType(), definedEventKeys,
                        "分子事件", metricKey);
                if (metricDefinition.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                        && (metricDefinition.getDenominatorEventType() == null
                        || metricDefinition.getDenominatorEventType().trim().isEmpty())) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "事件型分母必须配置分母事件: " + metricKey);
                }
                if (metricDefinition.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT) {
                    validateMetricEventReference(metricDefinition.getDenominatorEventType(), definedEventKeys,
                            "分母事件", metricKey);
                }
            }
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.COUNT
                    && metricDefinition.getNumeratorEventType() != null
                    && !metricDefinition.getNumeratorEventType().trim().isEmpty()) {
                validateMetricEventReference(metricDefinition.getNumeratorEventType(), definedEventKeys,
                        "指标事件", metricKey);
            }
            if (Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())) {
                primaryMetricCount++;
            }
        }

        if (primaryMetricCount == 0) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "必须配置一个主指标");
        }
        if (primaryMetricCount > 1) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "只能配置一个主指标");
        }
    }

    private void validateMetricEventReference(String eventKey, java.util.Set<String> definedEventKeys,
                                              String role, String metricKey) {
        String normalizedEventKey = normalizeDefinitionKey(eventKey, role);
        if (!definedEventKeys.contains(normalizedEventKey)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    role + "未在事件定义中声明: " + normalizedEventKey + "，指标: " + metricKey);
        }
    }

    private String normalizeDefinitionKey(String key) {
        return normalizeDefinitionKey(key, "编码");
    }

    private String normalizeDefinitionKey(String key, String fieldLabel) {
        String normalizedKey = requireTrimmedText(key, fieldLabel + "不能为空").toUpperCase();
        if (!DEFINITION_KEY_PATTERN.matcher(normalizedKey).matches()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    fieldLabel + "格式不合法，仅支持大写英文、数字和下划线: " + normalizedKey);
        }
        return normalizedKey;
    }

    private String requireTrimmedText(String value, String errorMessage) {
        String normalizedValue = trimToNull(value);
        if (normalizedValue == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, errorMessage);
        }
        return normalizedValue;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
