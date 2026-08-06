package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationSpace;
import com.pisces.common.model.Experiment;
import com.pisces.common.request.ApplicationSpaceUpsertRequest;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.common.response.ApplicationIntegrationHealthResponse;
import com.pisces.common.response.ApplicationSpaceResponse;
import com.pisces.service.entity.ExperimentFactAggregateEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.repository.ApplicationSpaceRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyRegistry;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ApplicationSpaceService;
import com.pisces.service.service.ApplicationDictionaryService;
import com.pisces.service.service.ExperimentService;
import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 应用空间服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:39
 */
@Service
@AllArgsConstructor
public class ApplicationSpaceServiceImpl implements ApplicationSpaceService {

    private static final String INTEGRATION_STATUS_READY = "READY";

    private static final String INTEGRATION_STATUS_ATTENTION = "ATTENTION";

    private static final String INTEGRATION_STATUS_BLOCKED = "BLOCKED";

    private static final String CHECK_STATUS_PASS = "PASS";

    private static final String CHECK_STATUS_WAITING = "WAITING";

    private static final String CHECK_STATUS_WARNING = "WARNING";

    private static final String CHECK_STATUS_BLOCKED = "BLOCKED";

    private static final int DEFAULT_APPROVAL_REQUIRED_COUNT = 1;

    private static final long DEFAULT_APPROVAL_POLICY_VERSION = 1L;

    private static final String DEFAULT_RELEASE_WINDOW_TIMEZONE = "Asia/Shanghai";

    private static final String DEFAULT_RELEASE_WINDOW_START_TIME = "09:00";

    private static final String DEFAULT_RELEASE_WINDOW_END_TIME = "18:00";

    private static final List<Integer> DEFAULT_RELEASE_WINDOW_DAYS = List.of(1, 2, 3, 4, 5);

    private static final DateTimeFormatter RELEASE_WINDOW_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    private final ApiKeyRegistry apiKeyRegistry;

    private final ExperimentService experimentService;

    private final ApplicationSpaceRepository applicationSpaceRepository;

    private final ApplicationDictionaryService applicationDictionaryService;

    private final ExperimentAssignmentRepository experimentAssignmentRepository;

    private final ExperimentExposureRepository experimentExposureRepository;

    private final ExperimentEventRepository experimentEventRepository;

    @Override
    public List<ApplicationSpaceResponse> listApplicationSpaces() {
        TreeMap<String, ApplicationSpaceBuilder> builders = new TreeMap<>();
        for (ApplicationSpace applicationSpace : findRegisteredSpaces()) {
            String appId = normalizeAppId(applicationSpace.getAppId());
            if (!canAccessApp(appId)) {
                continue;
            }
            builders.computeIfAbsent(appId, ApplicationSpaceBuilder::new)
                    .acceptApplicationSpace(applicationSpace);
        }

        for (ApiKeyPrincipal principal : apiKeyRegistry.listPrincipals()) {
            String appId = normalizeAppId(principal.getAppId());
            if (!canAccessApp(appId)) {
                continue;
            }
            builders.computeIfAbsent(appId, ApplicationSpaceBuilder::new)
                    .acceptPrincipal(principal);
        }

        for (Experiment experiment : experimentService.listExperiments(null, List.of(), null, null)) {
            String appId = normalizeAppId(experiment.getAppId());
            if (!canAccessApp(appId)) {
                continue;
            }
            builders.computeIfAbsent(appId, ApplicationSpaceBuilder::new)
                    .acceptExperiment(experiment);
        }

        return builders.values().stream()
                .map(ApplicationSpaceBuilder::build)
                .sorted(Comparator.comparing(ApplicationSpaceResponse::getAppId))
                .toList();
    }

    @Override
    public ApplicationIntegrationHealthResponse getIntegrationHealth(String appId) {
        String normalizedAppId = requireAppId(appId);
        if (!canAccessApp(normalizedAppId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权查看当前应用接入状态");
        }

        List<Experiment> experiments = experimentService.listExperiments(null, List.of(), normalizedAppId, null);
        ApplicationSpaceBuilder builder = new ApplicationSpaceBuilder(normalizedAppId);
        findRegisteredSpace(normalizedAppId).ifPresent(builder::acceptApplicationSpace);
        apiKeyRegistry.listPrincipals().stream()
                .filter(principal -> normalizedAppId.equals(normalizeAppId(principal.getAppId())))
                .forEach(builder::acceptPrincipal);
        experiments.forEach(builder::acceptExperiment);
        ApplicationSpaceResponse application = builder.build();
        if (!Boolean.TRUE.equals(application.getRegistered())
                && !Boolean.TRUE.equals(application.getConfigured())
                && experiments.isEmpty()) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "应用不存在");
        }

        ApplicationDictionaryResponse dictionary = applicationDictionaryService.getApplicationDictionary(normalizedAppId);
        List<String> experimentIds = experiments.stream().map(Experiment::getId).filter(Objects::nonNull).toList();
        ExperimentFactAggregateEntity assignments = experimentAssignmentRepository.aggregateByExperimentIds(experimentIds);
        ExperimentFactAggregateEntity exposures = experimentExposureRepository.aggregateByExperimentIds(experimentIds);
        ExperimentFactAggregateEntity events = experimentEventRepository.aggregateByExperimentIds(experimentIds);
        return buildIntegrationHealth(application, dictionary, experiments, assignments, exposures, events);
    }

    @Override
    public ApplicationSpaceResponse registerApplicationSpace(String appId, ApplicationSpaceUpsertRequest request) {
        String normalizedAppId = requireAppId(appId);
        if (findRegisteredSpace(normalizedAppId).isPresent()) {
            throw new BusinessException(ResponseCode.CONFLICT, "应用已注册，请勿重复注册");
        }
        ApplicationSpace applicationSpace = buildApplicationSpace(normalizedAppId, request, null);
        try {
            return buildApplicationSpaceResponse(applicationSpaceRepository.create(applicationSpace));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ResponseCode.CONFLICT, "应用已注册，请刷新后重试");
        }
    }

    @Override
    public ApplicationSpaceResponse upsertApplicationSpace(String appId, ApplicationSpaceUpsertRequest request) {
        String normalizedAppId = requireAppId(appId);
        assertCanManageApp(normalizedAppId);
        ApplicationSpace existingSpace = findRegisteredSpace(normalizedAppId).orElse(null);
        ApplicationSpace applicationSpace = buildApplicationSpace(normalizedAppId, request, existingSpace);
        ApplicationSpace savedSpace = applicationSpaceRepository.save(applicationSpace);
        return buildApplicationSpaceResponse(savedSpace);
    }

    private ApplicationIntegrationHealthResponse buildIntegrationHealth(
            ApplicationSpaceResponse application,
            ApplicationDictionaryResponse dictionary,
            List<Experiment> experiments,
            ExperimentFactAggregateEntity assignments,
            ExperimentFactAggregateEntity exposures,
            ExperimentFactAggregateEntity events) {
        int eventDefinitionCount = dictionary == null || dictionary.getEventDefinitions() == null
                ? 0 : dictionary.getEventDefinitions().size();
        int metricDefinitionCount = dictionary == null || dictionary.getMetricDefinitions() == null
                ? 0 : dictionary.getMetricDefinitions().size();
        long assignmentCount = resolveAggregateCount(assignments);
        long exposureCount = resolveAggregateCount(exposures);
        long eventCount = resolveAggregateCount(events);

        List<ApplicationIntegrationHealthResponse.CheckItem> checks = new ArrayList<>();
        checks.add(buildIntegrationCheck(
                "APPLICATION_REGISTERED",
                Boolean.TRUE.equals(application.getRegistered()) ? CHECK_STATUS_PASS : CHECK_STATUS_BLOCKED,
                "应用登记",
                Boolean.TRUE.equals(application.getRegistered())
                        ? "应用已完成登记，可以维护治理信息和业务字典"
                        : "当前应用仅来自访问配置或历史实验，尚未完成应用登记",
                Boolean.TRUE.equals(application.getRegistered()) ? null : "请先完成应用登记",
                Boolean.TRUE.equals(application.getRegistered()) ? 1L : 0L,
                "application"));
        checks.add(buildIntegrationCheck(
                "ACCESS_KEY_CONFIGURED",
                Boolean.TRUE.equals(application.getConfigured()) ? CHECK_STATUS_PASS : CHECK_STATUS_BLOCKED,
                "访问权限",
                Boolean.TRUE.equals(application.getConfigured())
                        ? "已配置" + application.getApiKeyCount() + "个应用访问身份"
                        : "当前应用没有可用的访问身份，客户端无法读取运行配置",
                Boolean.TRUE.equals(application.getConfigured()) ? null : "请在本地服务配置中绑定应用访问身份",
                Long.valueOf(application.getApiKeyCount()),
                "application"));
        boolean dictionaryReady = eventDefinitionCount > 0 && metricDefinitionCount > 0;
        checks.add(buildIntegrationCheck(
                "DICTIONARY_READY",
                dictionaryReady ? CHECK_STATUS_PASS : CHECK_STATUS_BLOCKED,
                "业务字典",
                dictionaryReady
                        ? "已维护" + eventDefinitionCount + "个事件和" + metricDefinitionCount + "个指标"
                        : "事件和指标必须同时维护，实验才能选择完整分析口径",
                dictionaryReady ? null : "请维护应用事件和指标字典",
                (long) eventDefinitionCount + metricDefinitionCount,
                "dictionary"));
        checks.add(buildIntegrationCheck(
                "EXPERIMENT_CONFIG_READY",
                experiments.isEmpty() ? CHECK_STATUS_WAITING : CHECK_STATUS_PASS,
                "实验配置",
                experiments.isEmpty()
                        ? "当前应用还没有实验运行配置"
                        : "已生成" + experiments.size() + "个实验配置，其中"
                                + application.getRunningExperimentCount() + "个正在运行",
                experiments.isEmpty() ? "请创建首个实验草稿" : null,
                (long) experiments.size(),
                "experiments"));
        checks.add(buildIntegrationCheck(
                "ASSIGNMENT_RECEIVED",
                assignmentCount > 0 ? CHECK_STATUS_PASS : CHECK_STATUS_WAITING,
                "用户分流",
                assignmentCount > 0 ? "已接收" + assignmentCount + "条真实分流事实" : "尚未收到真实用户分流事实",
                assignmentCount > 0 ? null : "请确认客户端已请求实验分流",
                assignmentCount,
                "runtime"));
        checks.add(buildIntegrationCheck(
                "EXPOSURE_RECEIVED",
                resolveDownstreamCheckStatus(assignmentCount, exposureCount),
                "变体曝光",
                exposureCount > 0 ? "已接收" + exposureCount + "条曝光事实" : "尚未收到用户实际看到变体的曝光事实",
                exposureCount > 0 ? null : "请在变体实际展示后上报曝光",
                exposureCount,
                "runtime"));
        checks.add(buildIntegrationCheck(
                "EVENT_RECEIVED",
                resolveDownstreamCheckStatus(exposureCount, eventCount),
                "业务事件",
                eventCount > 0 ? "已接收" + eventCount + "条业务事件事实" : "尚未收到用于计算指标的业务事件",
                eventCount > 0 ? null : "请确认事件编码与应用字典一致并完成上报",
                eventCount,
                "runtime"));

        ApplicationIntegrationHealthResponse response = new ApplicationIntegrationHealthResponse();
        response.setAppId(application.getAppId());
        response.setDisplayName(application.getDisplayName());
        response.setStatus(resolveIntegrationStatus(checks));
        response.setGeneratedAt(LocalDateTime.now());
        response.setEventDefinitionCount(eventDefinitionCount);
        response.setMetricDefinitionCount(metricDefinitionCount);
        response.setExperimentCount(experiments.size());
        response.setRunningExperimentCount(application.getRunningExperimentCount());
        response.setAssignmentCount(assignmentCount);
        response.setExposureCount(exposureCount);
        response.setEventCount(eventCount);
        response.setLatestActivityAt(resolveLatestActivity(assignments, exposures, events));
        response.setChecks(checks);
        return response;
    }

    private String resolveDownstreamCheckStatus(long upstreamCount, long currentCount) {
        if (currentCount > 0) {
            return CHECK_STATUS_PASS;
        }
        return upstreamCount > 0 ? CHECK_STATUS_WARNING : CHECK_STATUS_WAITING;
    }

    private String resolveIntegrationStatus(List<ApplicationIntegrationHealthResponse.CheckItem> checks) {
        if (checks.stream().anyMatch(check -> CHECK_STATUS_BLOCKED.equals(check.getStatus()))) {
            return INTEGRATION_STATUS_BLOCKED;
        }
        if (checks.stream().allMatch(check -> CHECK_STATUS_PASS.equals(check.getStatus()))) {
            return INTEGRATION_STATUS_READY;
        }
        return INTEGRATION_STATUS_ATTENTION;
    }

    private ApplicationIntegrationHealthResponse.CheckItem buildIntegrationCheck(
            String code,
            String status,
            String title,
            String detail,
            String action,
            Long evidenceCount,
            String target) {
        ApplicationIntegrationHealthResponse.CheckItem check =
                new ApplicationIntegrationHealthResponse.CheckItem();
        check.setCode(code);
        check.setStatus(status);
        check.setTitle(title);
        check.setDetail(detail);
        check.setAction(action);
        check.setEvidenceCount(evidenceCount);
        check.setTarget(target);
        return check;
    }

    private long resolveAggregateCount(ExperimentFactAggregateEntity aggregate) {
        return aggregate == null || aggregate.getTotalCount() == null ? 0L : aggregate.getTotalCount();
    }

    private LocalDateTime resolveLatestActivity(ExperimentFactAggregateEntity... aggregates) {
        LocalDateTime latest = null;
        for (ExperimentFactAggregateEntity aggregate : aggregates) {
            LocalDateTime candidate = aggregate == null ? null : aggregate.getLatestActivityAt();
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    private ApplicationSpace buildApplicationSpace(String appId, ApplicationSpaceUpsertRequest request,
                                                   ApplicationSpace existingSpace) {
        LocalDateTime now = LocalDateTime.now();
        String operator = ApiKeyContextHolder.resolveOperator(ApiKeyContextHolder.DEFAULT_OWNER);
        String defaultOwner = trimToNull(request != null ? request.getDefaultOwner() : null);
        Boolean approvalRequired = resolveApprovalRequired(request);
        List<String> approvalOwners = resolveApprovalOwners(request);
        int approvalRequiredCount = resolveApprovalRequiredCount(request);
        Integer approvalSlaHours = resolveApprovalSlaHours(request);
        List<String> approvalEscalationOwners = resolveApprovalEscalationOwners(request);
        Boolean releaseWindowEnabled = resolveReleaseWindowEnabled(request);
        String releaseWindowTimezone = resolveReleaseWindowTimezone(request, releaseWindowEnabled);
        List<Integer> releaseWindowDays = resolveReleaseWindowDays(request, releaseWindowEnabled);
        String releaseWindowStartTime = resolveReleaseWindowStartTime(request, releaseWindowEnabled);
        String releaseWindowEndTime = resolveReleaseWindowEndTime(request, releaseWindowEnabled);
        validateApprovalPolicy(approvalRequired, approvalOwners, defaultOwner, approvalRequiredCount);
        validateReleaseWindow(releaseWindowEnabled, releaseWindowTimezone, releaseWindowDays,
                releaseWindowStartTime, releaseWindowEndTime);
        long approvalPolicyVersion = resolveApprovalPolicyVersion(existingSpace, approvalRequired, approvalOwners,
                defaultOwner, approvalRequiredCount);
        ApplicationSpace applicationSpace = new ApplicationSpace();
        applicationSpace.setAppId(appId);
        applicationSpace.setDisplayName(resolveDisplayName(appId, request));
        applicationSpace.setDefaultOwner(defaultOwner);
        applicationSpace.setExperimentQuota(resolveExperimentQuota(request));
        applicationSpace.setApprovalRequired(approvalRequired);
        applicationSpace.setApprovalOwners(approvalOwners);
        applicationSpace.setApprovalRequiredCount(approvalRequiredCount);
        applicationSpace.setApprovalPolicyVersion(approvalPolicyVersion);
        applicationSpace.setApprovalSlaHours(approvalSlaHours);
        applicationSpace.setApprovalEscalationOwners(approvalEscalationOwners);
        applicationSpace.setReleaseWindowEnabled(releaseWindowEnabled);
        applicationSpace.setReleaseWindowTimezone(releaseWindowTimezone);
        applicationSpace.setReleaseWindowDays(releaseWindowDays);
        applicationSpace.setReleaseWindowStartTime(releaseWindowStartTime);
        applicationSpace.setReleaseWindowEndTime(releaseWindowEndTime);
        applicationSpace.setCreatedBy(existingSpace != null ? existingSpace.getCreatedBy() : operator);
        applicationSpace.setCreatedAt(existingSpace != null ? existingSpace.getCreatedAt() : now);
        applicationSpace.setUpdatedBy(operator);
        applicationSpace.setUpdatedAt(now);
        return applicationSpace;
    }

    private ApplicationSpaceResponse buildApplicationSpaceResponse(ApplicationSpace applicationSpace) {
        ApplicationSpaceBuilder builder = new ApplicationSpaceBuilder(normalizeAppId(applicationSpace.getAppId()));
        builder.acceptApplicationSpace(applicationSpace);
        for (ApiKeyPrincipal principal : apiKeyRegistry.listPrincipals()) {
            if (builder.appId.equals(normalizeAppId(principal.getAppId()))) {
                builder.acceptPrincipal(principal);
            }
        }
        for (Experiment experiment : experimentService.listExperiments(null, List.of(), null, null)) {
            if (builder.appId.equals(normalizeAppId(experiment.getAppId()))) {
                builder.acceptExperiment(experiment);
            }
        }
        return builder.build();
    }

    private List<ApplicationSpace> findRegisteredSpaces() {
        List<ApplicationSpace> applicationSpaces = applicationSpaceRepository.findAll();
        return applicationSpaces == null ? List.of() : applicationSpaces;
    }

    private Optional<ApplicationSpace> findRegisteredSpace(String appId) {
        Optional<ApplicationSpace> applicationSpaceOptional = applicationSpaceRepository.findByAppId(appId);
        return applicationSpaceOptional == null ? Optional.empty() : applicationSpaceOptional;
    }

    private void assertCanManageApp(String appId) {
        if (!canAccessApp(appId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权管理当前应用空间");
        }
    }

    private String requireAppId(String appId) {
        String normalizedAppId = trimToNull(appId);
        if (normalizedAppId == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "应用ID不能为空");
        }
        return normalizedAppId;
    }

    private String resolveDisplayName(String appId, ApplicationSpaceUpsertRequest request) {
        String displayName = trimToNull(request != null ? request.getDisplayName() : null);
        return displayName != null ? displayName : appId;
    }

    private Integer resolveExperimentQuota(ApplicationSpaceUpsertRequest request) {
        if (request == null || request.getExperimentQuota() == null) {
            return null;
        }
        Integer experimentQuota = request.getExperimentQuota();
        if (experimentQuota < 0) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验配额不能小于0");
        }
        return experimentQuota;
    }

    private Boolean resolveApprovalRequired(ApplicationSpaceUpsertRequest request) {
        return request != null && Boolean.TRUE.equals(request.getApprovalRequired());
    }

    private List<String> resolveApprovalOwners(ApplicationSpaceUpsertRequest request) {
        if (request == null || request.getApprovalOwners() == null || request.getApprovalOwners().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> approvalOwners = new LinkedHashSet<>();
        for (String approvalOwner : request.getApprovalOwners()) {
            String normalizedOwner = trimToNull(approvalOwner);
            if (normalizedOwner != null) {
                approvalOwners.add(normalizedOwner);
            }
        }
        return approvalOwners.stream().toList();
    }

    private int resolveApprovalRequiredCount(ApplicationSpaceUpsertRequest request) {
        if (request == null || request.getApprovalRequiredCount() == null) {
            return DEFAULT_APPROVAL_REQUIRED_COUNT;
        }
        int approvalRequiredCount = request.getApprovalRequiredCount();
        if (approvalRequiredCount < DEFAULT_APPROVAL_REQUIRED_COUNT) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "审批通过人数不能小于1");
        }
        return approvalRequiredCount;
    }

    private Integer resolveApprovalSlaHours(ApplicationSpaceUpsertRequest request) {
        if (request == null || request.getApprovalSlaHours() == null) {
            return null;
        }
        int approvalSlaHours = request.getApprovalSlaHours();
        if (approvalSlaHours < 1) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "审批 SLA 小时数必须是正整数");
        }
        return approvalSlaHours;
    }

    private List<String> resolveApprovalEscalationOwners(ApplicationSpaceUpsertRequest request) {
        if (request == null || request.getApprovalEscalationOwners() == null
                || request.getApprovalEscalationOwners().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> escalationOwners = new LinkedHashSet<>();
        for (String escalationOwner : request.getApprovalEscalationOwners()) {
            String normalizedOwner = trimToNull(escalationOwner);
            if (normalizedOwner != null) {
                escalationOwners.add(normalizedOwner);
            }
        }
        return escalationOwners.stream().toList();
    }

    private void validateApprovalPolicy(Boolean approvalRequired, List<String> approvalOwners, String defaultOwner,
                                        int approvalRequiredCount) {
        if (!Boolean.TRUE.equals(approvalRequired)) {
            return;
        }
        int approverCount = approvalOwners.isEmpty() && defaultOwner != null ? DEFAULT_APPROVAL_REQUIRED_COUNT
                : approvalOwners.size();
        if (approverCount == 0) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "启用审批时至少配置一个审批负责人或默认负责人");
        }
        if (approvalRequiredCount > approverCount) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "审批通过人数不能超过审批负责人数量: " + approverCount);
        }
    }

    private long resolveApprovalPolicyVersion(ApplicationSpace existingSpace, Boolean approvalRequired,
                                              List<String> approvalOwners, String defaultOwner,
                                              int approvalRequiredCount) {
        if (existingSpace == null) {
            return DEFAULT_APPROVAL_POLICY_VERSION;
        }
        long currentPolicyVersion = normalizeApprovalPolicyVersion(existingSpace.getApprovalPolicyVersion());
        if (isApprovalPolicyChanged(existingSpace, approvalRequired, approvalOwners, defaultOwner,
                approvalRequiredCount)) {
            return currentPolicyVersion + 1L;
        }
        return currentPolicyVersion;
    }

    private boolean isApprovalPolicyChanged(ApplicationSpace existingSpace, Boolean approvalRequired,
                                            List<String> approvalOwners, String defaultOwner,
                                            int approvalRequiredCount) {
        List<String> existingEffectiveOwners = resolveEffectiveApprovalOwners(
                normalizeApprovalOwners(existingSpace.getApprovalOwners()), trimToNull(existingSpace.getDefaultOwner()));
        List<String> requestedEffectiveOwners = resolveEffectiveApprovalOwners(approvalOwners, defaultOwner);
        return !Objects.equals(Boolean.TRUE.equals(existingSpace.getApprovalRequired()),
                Boolean.TRUE.equals(approvalRequired))
                || !Objects.equals(existingEffectiveOwners, requestedEffectiveOwners)
                || !Objects.equals(normalizeApprovalRequiredCount(existingSpace.getApprovalRequiredCount()),
                approvalRequiredCount);
    }

    private List<String> normalizeApprovalOwners(List<String> approvalOwners) {
        if (approvalOwners == null || approvalOwners.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalizedOwners = new LinkedHashSet<>();
        for (String approvalOwner : approvalOwners) {
            String normalizedOwner = trimToNull(approvalOwner);
            if (normalizedOwner != null) {
                normalizedOwners.add(normalizedOwner);
            }
        }
        return normalizedOwners.stream().toList();
    }

    private List<String> resolveEffectiveApprovalOwners(List<String> approvalOwners, String defaultOwner) {
        if (approvalOwners != null && !approvalOwners.isEmpty()) {
            return approvalOwners;
        }
        return defaultOwner == null ? List.of() : List.of(defaultOwner);
    }

    private int normalizeApprovalRequiredCount(Integer approvalRequiredCount) {
        if (approvalRequiredCount == null || approvalRequiredCount < DEFAULT_APPROVAL_REQUIRED_COUNT) {
            return DEFAULT_APPROVAL_REQUIRED_COUNT;
        }
        return approvalRequiredCount;
    }

    private long normalizeApprovalPolicyVersion(Long approvalPolicyVersion) {
        if (approvalPolicyVersion == null || approvalPolicyVersion < DEFAULT_APPROVAL_POLICY_VERSION) {
            return DEFAULT_APPROVAL_POLICY_VERSION;
        }
        return approvalPolicyVersion;
    }

    private Boolean resolveReleaseWindowEnabled(ApplicationSpaceUpsertRequest request) {
        return request != null && Boolean.TRUE.equals(request.getReleaseWindowEnabled());
    }

    private String resolveReleaseWindowTimezone(ApplicationSpaceUpsertRequest request, Boolean releaseWindowEnabled) {
        if (!Boolean.TRUE.equals(releaseWindowEnabled)) {
            return null;
        }
        String timezone = trimToNull(request != null ? request.getReleaseWindowTimezone() : null);
        return timezone != null ? timezone : DEFAULT_RELEASE_WINDOW_TIMEZONE;
    }

    private List<Integer> resolveReleaseWindowDays(ApplicationSpaceUpsertRequest request, Boolean releaseWindowEnabled) {
        if (!Boolean.TRUE.equals(releaseWindowEnabled)) {
            return List.of();
        }
        if (request == null || request.getReleaseWindowDays() == null || request.getReleaseWindowDays().isEmpty()) {
            return DEFAULT_RELEASE_WINDOW_DAYS;
        }
        LinkedHashSet<Integer> releaseWindowDays = new LinkedHashSet<>();
        for (Integer releaseWindowDay : request.getReleaseWindowDays()) {
            if (releaseWindowDay != null) {
                releaseWindowDays.add(releaseWindowDay);
            }
        }
        return releaseWindowDays.stream().toList();
    }

    private String resolveReleaseWindowStartTime(ApplicationSpaceUpsertRequest request, Boolean releaseWindowEnabled) {
        if (!Boolean.TRUE.equals(releaseWindowEnabled)) {
            return null;
        }
        String startTime = trimToNull(request != null ? request.getReleaseWindowStartTime() : null);
        return startTime != null ? startTime : DEFAULT_RELEASE_WINDOW_START_TIME;
    }

    private String resolveReleaseWindowEndTime(ApplicationSpaceUpsertRequest request, Boolean releaseWindowEnabled) {
        if (!Boolean.TRUE.equals(releaseWindowEnabled)) {
            return null;
        }
        String endTime = trimToNull(request != null ? request.getReleaseWindowEndTime() : null);
        return endTime != null ? endTime : DEFAULT_RELEASE_WINDOW_END_TIME;
    }

    private void validateReleaseWindow(Boolean releaseWindowEnabled, String timezone, List<Integer> releaseWindowDays,
                                       String startTime, String endTime) {
        if (!Boolean.TRUE.equals(releaseWindowEnabled)) {
            return;
        }
        validateReleaseWindowTimezone(timezone);
        validateReleaseWindowDays(releaseWindowDays);
        LocalTime parsedStartTime = parseReleaseWindowTime(startTime);
        LocalTime parsedEndTime = parseReleaseWindowTime(endTime);
        if (!parsedStartTime.isBefore(parsedEndTime)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "发布窗口开始时间必须早于结束时间");
        }
    }

    private void validateReleaseWindowTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "发布窗口时区无效: " + timezone);
        }
    }

    private void validateReleaseWindowDays(List<Integer> releaseWindowDays) {
        if (releaseWindowDays == null || releaseWindowDays.isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "发布窗口星期不能为空");
        }
        for (Integer releaseWindowDay : releaseWindowDays) {
            if (releaseWindowDay == null || releaseWindowDay < 1 || releaseWindowDay > 7) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "发布窗口星期必须在1到7之间");
            }
        }
    }

    private LocalTime parseReleaseWindowTime(String releaseWindowTime) {
        try {
            return LocalTime.parse(releaseWindowTime, RELEASE_WINDOW_TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    "发布窗口时间必须使用 HH:mm 格式: " + releaseWindowTime);
        }
    }

    private boolean canAccessApp(String appId) {
        return ApiKeyContextHolder.get()
                .map(principal -> ApiKeyContextHolder.isAdmin(principal)
                        || normalizeAppId(principal.getAppId()).equals(appId))
                .orElse(true);
    }

    private static String normalizeAppId(String appId) {
        if (StringUtils.hasText(appId)) {
            return appId.trim();
        }
        return ApiKeyContextHolder.DEFAULT_APP_ID;
    }

    private static String normalizeOwner(String owner) {
        if (StringUtils.hasText(owner)) {
            return owner.trim();
        }
        return ApiKeyContextHolder.DEFAULT_OWNER;
    }

    private static String trimToNull(String value) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return null;
    }

    private static final class ApplicationSpaceBuilder {

        private final String appId;

        private String displayName;

        private String defaultOwner;

        private Integer experimentQuota;

        private Boolean approvalRequired;

        private final Set<String> approvalOwners = new LinkedHashSet<>();

        private Integer approvalRequiredCount = DEFAULT_APPROVAL_REQUIRED_COUNT;

        private Long approvalPolicyVersion = DEFAULT_APPROVAL_POLICY_VERSION;

        private Integer approvalSlaHours;

        private final Set<String> approvalEscalationOwners = new LinkedHashSet<>();

        private Boolean releaseWindowEnabled;

        private String releaseWindowTimezone;

        private List<Integer> releaseWindowDays = List.of();

        private String releaseWindowStartTime;

        private String releaseWindowEndTime;

        private final Set<String> owners = new LinkedHashSet<>();

        private final Set<String> scopes = new LinkedHashSet<>();

        private boolean registered;

        private boolean configured;

        private int apiKeyCount;

        private int experimentCount;

        private int runningExperimentCount;

        private ApplicationSpaceBuilder(String appId) {
            this.appId = appId;
            this.displayName = appId;
        }

        private ApplicationSpaceBuilder acceptApplicationSpace(ApplicationSpace applicationSpace) {
            registered = true;
            displayName = StringUtils.hasText(applicationSpace.getDisplayName())
                    ? applicationSpace.getDisplayName().trim() : appId;
            defaultOwner = trimToNull(applicationSpace.getDefaultOwner());
            experimentQuota = applicationSpace.getExperimentQuota();
            approvalRequired = Boolean.TRUE.equals(applicationSpace.getApprovalRequired());
            approvalRequiredCount = normalizeApprovalRequiredCount(applicationSpace.getApprovalRequiredCount());
            approvalPolicyVersion = normalizeApprovalPolicyVersion(applicationSpace.getApprovalPolicyVersion());
            approvalSlaHours = normalizeApprovalSlaHours(applicationSpace.getApprovalSlaHours());
            approvalEscalationOwners.addAll(normalizeOwners(applicationSpace.getApprovalEscalationOwners()));
            releaseWindowEnabled = Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled());
            releaseWindowTimezone = resolveReleaseWindowTimezone(applicationSpace);
            releaseWindowDays = resolveReleaseWindowDays(applicationSpace);
            releaseWindowStartTime = resolveReleaseWindowStartTime(applicationSpace);
            releaseWindowEndTime = resolveReleaseWindowEndTime(applicationSpace);
            List<String> effectiveApprovalOwners = resolveEffectiveApprovalOwners(applicationSpace);
            approvalOwners.addAll(effectiveApprovalOwners);
            owners.addAll(approvalEscalationOwners);
            owners.addAll(effectiveApprovalOwners);
            if (defaultOwner != null) {
                owners.add(defaultOwner);
            }
            return this;
        }

        private ApplicationSpaceBuilder acceptPrincipal(ApiKeyPrincipal principal) {
            configured = true;
            apiKeyCount++;
            owners.add(normalizeOwner(principal.getOwner()));
            if (principal.getScopes() != null) {
                principal.getScopes().stream()
                        .map(ApiKeyScope::name)
                        .sorted()
                        .forEach(scopes::add);
            }
            return this;
        }

        private ApplicationSpaceBuilder acceptExperiment(Experiment experiment) {
            experimentCount++;
            String owner = StringUtils.hasText(experiment.getOwner())
                    ? experiment.getOwner() : experiment.getCreator();
            owners.add(normalizeOwner(owner));
            if (Experiment.ExperimentStatus.RUNNING.equals(experiment.getStatus())) {
                runningExperimentCount++;
            }
            return this;
        }

        private ApplicationSpaceResponse build() {
            ApplicationSpaceResponse response = new ApplicationSpaceResponse();
            response.setAppId(appId);
            response.setDisplayName(displayName);
            response.setDefaultOwner(defaultOwner);
            response.setExperimentQuota(experimentQuota);
            response.setQuotaUsed(experimentCount);
            response.setQuotaRemaining(experimentQuota == null ? null : Math.max(0, experimentQuota - experimentCount));
            response.setApprovalRequired(Boolean.TRUE.equals(approvalRequired));
            response.setApprovalOwners(new ArrayList<>(approvalOwners));
            response.setApprovalRequiredCount(approvalRequiredCount);
            response.setApprovalPolicyVersion(approvalPolicyVersion);
            response.setApprovalSlaHours(approvalSlaHours);
            response.setApprovalEscalationOwners(new ArrayList<>(approvalEscalationOwners));
            response.setReleaseWindowEnabled(Boolean.TRUE.equals(releaseWindowEnabled));
            response.setReleaseWindowTimezone(releaseWindowTimezone);
            response.setReleaseWindowDays(releaseWindowDays);
            response.setReleaseWindowStartTime(releaseWindowStartTime);
            response.setReleaseWindowEndTime(releaseWindowEndTime);
            response.setOwners(new ArrayList<>(owners));
            response.setScopes(scopes.stream().sorted().toList());
            response.setConfigured(configured);
            response.setRegistered(registered);
            response.setApiKeyCount(apiKeyCount);
            response.setExperimentCount(experimentCount);
            response.setRunningExperimentCount(runningExperimentCount);
            return response;
        }

        private List<String> resolveEffectiveApprovalOwners(ApplicationSpace applicationSpace) {
            if (applicationSpace.getApprovalOwners() != null && !applicationSpace.getApprovalOwners().isEmpty()) {
                return applicationSpace.getApprovalOwners().stream()
                        .map(ApplicationSpaceServiceImpl::trimToNull)
                        .filter(owner -> owner != null)
                        .distinct()
                        .toList();
            }
            String normalizedDefaultOwner = trimToNull(applicationSpace.getDefaultOwner());
            return normalizedDefaultOwner == null ? List.of() : List.of(normalizedDefaultOwner);
        }

        private int normalizeApprovalRequiredCount(Integer approvalRequiredCount) {
            if (approvalRequiredCount == null || approvalRequiredCount < DEFAULT_APPROVAL_REQUIRED_COUNT) {
                return DEFAULT_APPROVAL_REQUIRED_COUNT;
            }
            return approvalRequiredCount;
        }

        private Integer normalizeApprovalSlaHours(Integer approvalSlaHours) {
            if (approvalSlaHours == null || approvalSlaHours < 1) {
                return null;
            }
            return approvalSlaHours;
        }

        private long normalizeApprovalPolicyVersion(Long approvalPolicyVersion) {
            if (approvalPolicyVersion == null || approvalPolicyVersion < DEFAULT_APPROVAL_POLICY_VERSION) {
                return DEFAULT_APPROVAL_POLICY_VERSION;
            }
            return approvalPolicyVersion;
        }

        private List<String> normalizeOwners(List<String> sourceOwners) {
            if (sourceOwners == null || sourceOwners.isEmpty()) {
                return List.of();
            }
            return sourceOwners.stream()
                    .map(ApplicationSpaceServiceImpl::trimToNull)
                    .filter(owner -> owner != null)
                    .distinct()
                    .toList();
        }

        private String resolveReleaseWindowTimezone(ApplicationSpace applicationSpace) {
            if (!Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
                return null;
            }
            return trimToNull(applicationSpace.getReleaseWindowTimezone()) != null
                    ? applicationSpace.getReleaseWindowTimezone().trim() : DEFAULT_RELEASE_WINDOW_TIMEZONE;
        }

        private List<Integer> resolveReleaseWindowDays(ApplicationSpace applicationSpace) {
            if (!Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
                return List.of();
            }
            if (applicationSpace.getReleaseWindowDays() == null || applicationSpace.getReleaseWindowDays().isEmpty()) {
                return DEFAULT_RELEASE_WINDOW_DAYS;
            }
            return applicationSpace.getReleaseWindowDays().stream()
                    .filter(day -> day != null)
                    .distinct()
                    .toList();
        }

        private String resolveReleaseWindowStartTime(ApplicationSpace applicationSpace) {
            if (!Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
                return null;
            }
            return trimToNull(applicationSpace.getReleaseWindowStartTime()) != null
                    ? applicationSpace.getReleaseWindowStartTime().trim() : DEFAULT_RELEASE_WINDOW_START_TIME;
        }

        private String resolveReleaseWindowEndTime(ApplicationSpace applicationSpace) {
            if (!Boolean.TRUE.equals(applicationSpace.getReleaseWindowEnabled())) {
                return null;
            }
            return trimToNull(applicationSpace.getReleaseWindowEndTime()) != null
                    ? applicationSpace.getReleaseWindowEndTime().trim() : DEFAULT_RELEASE_WINDOW_END_TIME;
        }
    }
}
