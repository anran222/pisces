package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationMetricDefinition;
import com.pisces.common.model.ApplicationSpace;
import com.pisces.common.model.Experiment;
import com.pisces.common.request.ApplicationSpaceUpsertRequest;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.common.response.ApplicationIntegrationHealthResponse;
import com.pisces.common.response.ApplicationSpaceResponse;
import com.pisces.service.entity.ExperimentFactAggregateEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ApplicationSpaceRepository;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyRegistry;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ApplicationDictionaryService;
import com.pisces.service.service.ExperimentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用空间服务测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:39
 */
@ExtendWith(MockitoExtension.class)
class ApplicationSpaceServiceImplTest {

    @Mock
    private ApiKeyRegistry apiKeyRegistry;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private ApplicationSpaceRepository applicationSpaceRepository;

    @Mock
    private ApplicationDictionaryService applicationDictionaryService;

    @Mock
    private ExperimentAssignmentRepository experimentAssignmentRepository;

    @Mock
    private ExperimentExposureRepository experimentExposureRepository;

    @Mock
    private ExperimentEventRepository experimentEventRepository;

    @InjectMocks
    private ApplicationSpaceServiceImpl applicationSpaceService;

    @AfterEach
    void tearDown() {
        ApiKeyContextHolder.clear();
    }

    @Test
    void listApplicationSpacesShouldMergeConfiguredAndExperimentOnlySpacesForAdmin() {
        ApiKeyContextHolder.set(principal("ops", "platform", ApiKeyScope.ADMIN));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of(
                principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT),
                principal("app-a", "analyst-a", ApiKeyScope.ANALYSIS),
                principal("app-b", "sdk-b", ApiKeyScope.RUNTIME)
        ));
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull()))
                .thenReturn(List.of(
                        experiment("app-a", "owner-a", Experiment.ExperimentStatus.RUNNING),
                        experiment("app-c", "owner-c", Experiment.ExperimentStatus.DRAFT)
                ));

        List<ApplicationSpaceResponse> spaces = applicationSpaceService.listApplicationSpaces();

        assertThat(spaces).extracting(ApplicationSpaceResponse::getAppId)
                .containsExactly("app-a", "app-b", "app-c");
        ApplicationSpaceResponse appA = findSpace(spaces, "app-a");
        assertThat(appA.getConfigured()).isTrue();
        assertThat(appA.getApiKeyCount()).isEqualTo(2);
        assertThat(appA.getExperimentCount()).isEqualTo(1);
        assertThat(appA.getRunningExperimentCount()).isEqualTo(1);
        assertThat(appA.getOwners()).containsExactly("owner-a", "analyst-a");
        assertThat(appA.getScopes()).containsExactly("ANALYSIS", "MANAGEMENT");

        ApplicationSpaceResponse appC = findSpace(spaces, "app-c");
        assertThat(appC.getConfigured()).isFalse();
        assertThat(appC.getApiKeyCount()).isZero();
        assertThat(appC.getExperimentCount()).isEqualTo(1);
        assertThat(appC.getOwners()).containsExactly("owner-c");
    }

    @Test
    void listApplicationSpacesShouldMergeRegisteredSpaceGovernanceFields() {
        ApiKeyContextHolder.set(principal("ops", "platform", ApiKeyScope.ADMIN));
        ApplicationSpace registeredSpace = applicationSpace("app-a", "交易应用", "pm-a", 3);
        registeredSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        registeredSpace.setApprovalSlaHours(12);
        registeredSpace.setApprovalEscalationOwners(List.of("ops-a"));
        when(applicationSpaceRepository.findAll()).thenReturn(List.of(registeredSpace));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of(
                principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT)
        ));
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull()))
                .thenReturn(List.of(
                        experiment("app-a", "owner-a", Experiment.ExperimentStatus.RUNNING),
                        experiment("app-a", "owner-b", Experiment.ExperimentStatus.DRAFT)
                ));

        List<ApplicationSpaceResponse> spaces = applicationSpaceService.listApplicationSpaces();

        ApplicationSpaceResponse appA = findSpace(spaces, "app-a");
        assertThat(appA.getRegistered()).isTrue();
        assertThat(appA.getDisplayName()).isEqualTo("交易应用");
        assertThat(appA.getDefaultOwner()).isEqualTo("pm-a");
        assertThat(appA.getExperimentQuota()).isEqualTo(3);
        assertThat(appA.getApprovalRequired()).isFalse();
        assertThat(appA.getApprovalOwners()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(appA.getApprovalSlaHours()).isEqualTo(12);
        assertThat(appA.getApprovalEscalationOwners()).containsExactly("ops-a");
        assertThat(appA.getQuotaUsed()).isEqualTo(2);
        assertThat(appA.getQuotaRemaining()).isEqualTo(1);
        assertThat(appA.getOwners()).containsExactly("ops-a", "reviewer-a", "reviewer-b", "pm-a",
                "owner-a", "owner-b");
        assertThat(appA.getReleaseWindowEnabled()).isFalse();
    }

    @Test
    void listApplicationSpacesShouldRestrictNonAdminToOwnApp() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of(
                principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT),
                principal("app-b", "owner-b", ApiKeyScope.MANAGEMENT)
        ));
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull()))
                .thenReturn(List.of(
                        experiment("app-a", "owner-a", Experiment.ExperimentStatus.RUNNING),
                        experiment("app-b", "owner-b", Experiment.ExperimentStatus.RUNNING)
                ));

        List<ApplicationSpaceResponse> spaces = applicationSpaceService.listApplicationSpaces();

        assertThat(spaces).extracting(ApplicationSpaceResponse::getAppId)
                .containsExactly("app-a");
        ApplicationSpaceResponse appA = findSpace(spaces, "app-a");
        assertThat(appA.getApiKeyCount()).isEqualTo(1);
        assertThat(appA.getExperimentCount()).isEqualTo(1);
        assertThat(appA.getRunningExperimentCount()).isEqualTo(1);
    }

    @Test
    void getIntegrationHealthShouldAggregateEveryRuntimeFactOnce() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpace registeredSpace = applicationSpace("shop-app", "二手手机商城", "anran", 20);
        Experiment runningExperiment = experiment("shop-app", "anran", Experiment.ExperimentStatus.RUNNING);
        ApplicationDictionaryResponse dictionary = new ApplicationDictionaryResponse();
        dictionary.setEventDefinitions(List.of(new ApplicationEventDefinition()));
        dictionary.setMetricDefinitions(List.of(new ApplicationMetricDefinition()));
        ExperimentFactAggregateEntity assignments = aggregate(120L, LocalDateTime.of(2026, 8, 6, 10, 0));
        ExperimentFactAggregateEntity exposures = aggregate(110L, LocalDateTime.of(2026, 8, 6, 10, 5));
        ExperimentFactAggregateEntity events = aggregate(80L, LocalDateTime.of(2026, 8, 6, 10, 10));
        when(applicationSpaceRepository.findByAppId("shop-app")).thenReturn(Optional.of(registeredSpace));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of(
                principal("shop-app", "anran", ApiKeyScope.RUNTIME)));
        when(experimentService.listExperiments(isNull(), anyList(), eq("shop-app"), isNull()))
                .thenReturn(List.of(runningExperiment));
        when(applicationDictionaryService.getApplicationDictionary("shop-app")).thenReturn(dictionary);
        when(experimentAssignmentRepository.aggregateByExperimentIds(List.of(runningExperiment.getId())))
                .thenReturn(assignments);
        when(experimentExposureRepository.aggregateByExperimentIds(List.of(runningExperiment.getId())))
                .thenReturn(exposures);
        when(experimentEventRepository.aggregateByExperimentIds(List.of(runningExperiment.getId())))
                .thenReturn(events);

        ApplicationIntegrationHealthResponse response =
                applicationSpaceService.getIntegrationHealth("shop-app");

        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getChecks()).hasSize(7)
                .allMatch(check -> "PASS".equals(check.getStatus()));
        assertThat(response.getAssignmentCount()).isEqualTo(120L);
        assertThat(response.getExposureCount()).isEqualTo(110L);
        assertThat(response.getEventCount()).isEqualTo(80L);
        assertThat(response.getLatestActivityAt()).isEqualTo(LocalDateTime.of(2026, 8, 6, 10, 10));
        verify(experimentAssignmentRepository).aggregateByExperimentIds(List.of(runningExperiment.getId()));
        verify(experimentExposureRepository).aggregateByExperimentIds(List.of(runningExperiment.getId()));
        verify(experimentEventRepository).aggregateByExperimentIds(List.of(runningExperiment.getId()));
    }

    @Test
    void getIntegrationHealthShouldBlockWhenRegistrationHasNoAccessOrDictionary() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpace registeredSpace = applicationSpace("shop-app", "二手手机商城", "anran", 20);
        ApplicationDictionaryResponse dictionary = new ApplicationDictionaryResponse();
        dictionary.setEventDefinitions(List.of());
        dictionary.setMetricDefinitions(List.of());
        when(applicationSpaceRepository.findByAppId("shop-app")).thenReturn(Optional.of(registeredSpace));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of());
        when(experimentService.listExperiments(isNull(), anyList(), eq("shop-app"), isNull()))
                .thenReturn(List.of());
        when(applicationDictionaryService.getApplicationDictionary("shop-app")).thenReturn(dictionary);

        ApplicationIntegrationHealthResponse response =
                applicationSpaceService.getIntegrationHealth("shop-app");

        assertThat(response.getStatus()).isEqualTo("BLOCKED");
        assertThat(response.getChecks())
                .filteredOn(check -> "BLOCKED".equals(check.getStatus()))
                .extracting(ApplicationIntegrationHealthResponse.CheckItem::getCode)
                .containsExactly("ACCESS_KEY_CONFIGURED", "DICTIONARY_READY");
        assertThat(response.getAssignmentCount()).isZero();
        assertThat(response.getExposureCount()).isZero();
        assertThat(response.getEventCount()).isZero();
    }

    @Test
    void getIntegrationHealthShouldWaitForFactsAfterConfigurationIsReady() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpace registeredSpace = applicationSpace("shop-app", "二手手机商城", "anran", 20);
        Experiment draftExperiment = experiment("shop-app", "anran", Experiment.ExperimentStatus.DRAFT);
        ApplicationDictionaryResponse dictionary = new ApplicationDictionaryResponse();
        dictionary.setEventDefinitions(List.of(new ApplicationEventDefinition()));
        dictionary.setMetricDefinitions(List.of(new ApplicationMetricDefinition()));
        when(applicationSpaceRepository.findByAppId("shop-app")).thenReturn(Optional.of(registeredSpace));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of(
                principal("shop-app", "anran", ApiKeyScope.RUNTIME)));
        when(experimentService.listExperiments(isNull(), anyList(), eq("shop-app"), isNull()))
                .thenReturn(List.of(draftExperiment));
        when(applicationDictionaryService.getApplicationDictionary("shop-app")).thenReturn(dictionary);

        ApplicationIntegrationHealthResponse response =
                applicationSpaceService.getIntegrationHealth("shop-app");

        assertThat(response.getStatus()).isEqualTo("ATTENTION");
        assertThat(response.getChecks()).satisfiesExactly(
                check -> assertThat(check.getStatus()).isEqualTo("PASS"),
                check -> assertThat(check.getStatus()).isEqualTo("PASS"),
                check -> assertThat(check.getStatus()).isEqualTo("PASS"),
                check -> assertThat(check.getStatus()).isEqualTo("PASS"),
                check -> assertThat(check.getStatus()).isEqualTo("WAITING"),
                check -> assertThat(check.getStatus()).isEqualTo("WAITING"),
                check -> assertThat(check.getStatus()).isEqualTo("WAITING"));
    }

    @Test
    void getIntegrationHealthShouldRejectCrossApplicationAccess() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        assertThatThrownBy(() -> applicationSpaceService.getIntegrationHealth("shop-app"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.FORBIDDEN));
    }

    @Test
    void upsertApplicationSpaceShouldSaveRegisteredSpace() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDisplayName(" 交易应用 ");
        request.setDefaultOwner(" pm-a ");
        request.setExperimentQuota(10);
        request.setApprovalRequired(true);
        request.setApprovalOwners(List.of(" reviewer-a ", "pm-a", "reviewer-a"));
        request.setApprovalRequiredCount(2);
        request.setApprovalSlaHours(8);
        request.setApprovalEscalationOwners(List.of(" ops-a ", "lead-a", "ops-a"));
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.empty());
        when(applicationSpaceRepository.save(any(ApplicationSpace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of());
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull())).thenReturn(List.of());

        ApplicationSpaceResponse response = applicationSpaceService.upsertApplicationSpace("app-a", request);

        assertThat(response.getRegistered()).isTrue();
        assertThat(response.getDisplayName()).isEqualTo("交易应用");
        assertThat(response.getDefaultOwner()).isEqualTo("pm-a");
        assertThat(response.getExperimentQuota()).isEqualTo(10);
        assertThat(response.getApprovalRequired()).isTrue();
        assertThat(response.getApprovalOwners()).containsExactly("reviewer-a", "pm-a");
        assertThat(response.getApprovalRequiredCount()).isEqualTo(2);
        assertThat(response.getApprovalPolicyVersion()).isEqualTo(1L);
        assertThat(response.getApprovalSlaHours()).isEqualTo(8);
        assertThat(response.getApprovalEscalationOwners()).containsExactly("ops-a", "lead-a");
        verify(applicationSpaceRepository).save(any(ApplicationSpace.class));
    }

    @Test
    void upsertApplicationSpaceShouldSaveReleaseWindow() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDisplayName("交易应用");
        request.setDefaultOwner("pm-a");
        request.setReleaseWindowEnabled(true);
        request.setReleaseWindowTimezone(" Asia/Shanghai ");
        request.setReleaseWindowDays(List.of(1, 2, 5));
        request.setReleaseWindowStartTime(" 10:00 ");
        request.setReleaseWindowEndTime(" 16:30 ");
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.empty());
        when(applicationSpaceRepository.save(any(ApplicationSpace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of());
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull())).thenReturn(List.of());

        ApplicationSpaceResponse response = applicationSpaceService.upsertApplicationSpace("app-a", request);

        assertThat(response.getReleaseWindowEnabled()).isTrue();
        assertThat(response.getReleaseWindowTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.getReleaseWindowDays()).containsExactly(1, 2, 5);
        assertThat(response.getReleaseWindowStartTime()).isEqualTo("10:00");
        assertThat(response.getReleaseWindowEndTime()).isEqualTo("16:30");
    }

    @Test
    void upsertApplicationSpaceShouldRejectInvalidReleaseWindowTimeRange() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDefaultOwner("pm-a");
        request.setReleaseWindowEnabled(true);
        request.setReleaseWindowStartTime("18:00");
        request.setReleaseWindowEndTime("09:00");
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationSpaceService.upsertApplicationSpace("app-a", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发布窗口开始时间必须早于结束时间");
    }

    @Test
    void upsertApplicationSpaceShouldRejectInvalidApprovalSlaHours() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDefaultOwner("pm-a");
        request.setApprovalSlaHours(0);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationSpaceService.upsertApplicationSpace("app-a", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批 SLA 小时数必须是正整数");
    }

    @Test
    void upsertApplicationSpaceShouldIncrementApprovalPolicyVersionWhenPolicyChanges() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpace existingSpace = applicationSpace("app-a", "交易应用", "pm-a", 10);
        existingSpace.setApprovalRequired(true);
        existingSpace.setApprovalOwners(List.of("reviewer-a"));
        existingSpace.setApprovalRequiredCount(1);
        existingSpace.setApprovalPolicyVersion(4L);
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDisplayName("交易应用");
        request.setDefaultOwner("pm-a");
        request.setExperimentQuota(10);
        request.setApprovalRequired(true);
        request.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        request.setApprovalRequiredCount(2);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(existingSpace));
        when(applicationSpaceRepository.save(any(ApplicationSpace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of());
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull())).thenReturn(List.of());

        ApplicationSpaceResponse response = applicationSpaceService.upsertApplicationSpace("app-a", request);

        assertThat(response.getApprovalPolicyVersion()).isEqualTo(5L);
        assertThat(response.getApprovalOwners()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(response.getApprovalRequiredCount()).isEqualTo(2);
    }

    @Test
    void upsertApplicationSpaceShouldRejectApprovalRequiredCountGreaterThanApprovers() {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDefaultOwner("pm-a");
        request.setApprovalRequired(true);
        request.setApprovalOwners(List.of("reviewer-a"));
        request.setApprovalRequiredCount(2);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicationSpaceService.upsertApplicationSpace("app-a", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批通过人数不能超过审批负责人数量: 1");
    }

    @Test
    void registerApplicationSpaceShouldAllowNonAdminToCreateNewApp() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDisplayName("应用B");
        request.setDefaultOwner("owner-b");
        when(applicationSpaceRepository.findByAppId("app-b")).thenReturn(Optional.empty());
        when(applicationSpaceRepository.create(any(ApplicationSpace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRegistry.listPrincipals()).thenReturn(List.of());
        when(experimentService.listExperiments(isNull(), anyList(), isNull(), isNull())).thenReturn(List.of());

        ApplicationSpaceResponse response = applicationSpaceService.registerApplicationSpace("app-b", request);

        assertThat(response.getAppId()).isEqualTo("app-b");
        assertThat(response.getDisplayName()).isEqualTo("应用B");
        assertThat(response.getRegistered()).isTrue();
        verify(applicationSpaceRepository).create(any(ApplicationSpace.class));
    }

    @Test
    void registerApplicationSpaceShouldRejectRegisteredApp() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(applicationSpaceRepository.findByAppId("app-b"))
                .thenReturn(Optional.of(applicationSpace("app-b", "应用B", "owner-b", 10)));

        assertThatThrownBy(() -> applicationSpaceService.registerApplicationSpace("app-b",
                new ApplicationSpaceUpsertRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT));
    }

    @Test
    void registerApplicationSpaceShouldHandleConcurrentRegistration() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        ApplicationSpaceUpsertRequest request = new ApplicationSpaceUpsertRequest();
        request.setDefaultOwner("owner-b");
        when(applicationSpaceRepository.findByAppId("app-b")).thenReturn(Optional.empty());
        when(applicationSpaceRepository.create(any(ApplicationSpace.class)))
                .thenThrow(new DuplicateKeyException("duplicate app id"));

        assertThatThrownBy(() -> applicationSpaceService.registerApplicationSpace("app-b", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT));
    }

    @Test
    void upsertApplicationSpaceShouldRejectOtherAppForNonAdmin() {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        assertThatThrownBy(() -> applicationSpaceService.upsertApplicationSpace("app-b",
                new ApplicationSpaceUpsertRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.FORBIDDEN));
    }

    private ApplicationSpaceResponse findSpace(List<ApplicationSpaceResponse> spaces, String appId) {
        return spaces.stream()
                .filter(space -> appId.equals(space.getAppId()))
                .findFirst()
                .orElseThrow();
    }

    private ApiKeyPrincipal principal(String appId, String owner, ApiKeyScope firstScope,
                                      ApiKeyScope... remainingScopes) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(appId);
        principal.setOwner(owner);
        principal.setScopes(EnumSet.of(firstScope, remainingScopes));
        return principal;
    }

    private Experiment experiment(String appId, String owner, Experiment.ExperimentStatus status) {
        Experiment experiment = new Experiment();
        experiment.setId("exp_" + appId + "_" + status.name());
        experiment.setName("实验-" + appId);
        experiment.setAppId(appId);
        experiment.setOwner(owner);
        experiment.setStatus(status);
        return experiment;
    }

    private ApplicationSpace applicationSpace(String appId, String displayName, String defaultOwner,
                                              Integer experimentQuota) {
        ApplicationSpace applicationSpace = new ApplicationSpace();
        applicationSpace.setAppId(appId);
        applicationSpace.setDisplayName(displayName);
        applicationSpace.setDefaultOwner(defaultOwner);
        applicationSpace.setExperimentQuota(experimentQuota);
        return applicationSpace;
    }

    private ExperimentFactAggregateEntity aggregate(long count, LocalDateTime latestActivityAt) {
        ExperimentFactAggregateEntity aggregate = new ExperimentFactAggregateEntity();
        aggregate.setTotalCount(count);
        aggregate.setLatestActivityAt(latestActivityAt);
        return aggregate;
    }
}
