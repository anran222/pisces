package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationSpace;
import com.pisces.common.model.Experiment;
import com.pisces.common.request.ApplicationSpaceUpsertRequest;
import com.pisces.common.response.ApplicationSpaceResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ApplicationSpaceRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyRegistry;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ApplicationSpaceService;
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
