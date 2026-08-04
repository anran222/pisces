package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ApplicationEventDefinition;
import com.pisces.common.model.ApplicationSpace;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentApprovalEscalation;
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
import com.pisces.common.request.ExperimentApprovalEscalationAcknowledgeRequest;
import com.pisces.common.request.ExperimentApprovalStatusUpdateRequest;
import com.pisces.common.request.ExperimentConfigDraftSaveRequest;
import com.pisces.common.request.ExperimentConfigPublishRequest;
import com.pisces.common.request.ExperimentConfigRollbackRequest;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentApprovalEscalationOperationResponse;
import com.pisces.common.response.ApplicationDictionaryResponse;
import com.pisces.common.response.ExperimentApprovalEscalationResponse;
import com.pisces.common.response.ExperimentApprovalEscalationStatusResponse;
import com.pisces.common.response.ExperimentApprovalTaskResponse;
import com.pisces.common.response.ExperimentConfigDraftApprovalResponse;
import com.pisces.common.response.ExperimentConfigDraftResponse;
import com.pisces.common.response.ExperimentConfigVersionResponse;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.audit.AuditLogRecord;
import com.pisces.service.entity.ExperimentApprovalEscalationStatusCountEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ApplicationSpaceRepository;
import com.pisces.service.repository.ExperimentApprovalEscalationRepository;
import com.pisces.service.repository.ExperimentApprovalVoteRepository;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ApprovalEscalationNotificationDispatcher;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.ApplicationDictionaryService;
import com.pisces.service.service.AuditLogService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceImplTest {

    @Test
    void configDraftMutationsShouldRunInsideTransactions() throws NoSuchMethodException {
        Transactional saveDraftTransaction = ExperimentServiceImpl.class
                .getMethod("saveConfigDraft", String.class, ExperimentConfigDraftSaveRequest.class)
                .getAnnotation(Transactional.class);
        Transactional publishDraftTransaction = ExperimentServiceImpl.class
                .getMethod("publishConfigDraft", String.class, ExperimentConfigPublishRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(saveDraftTransaction).isNotNull();
        assertThat(publishDraftTransaction).isNotNull();
        assertThat(saveDraftTransaction.rollbackFor()).contains(Exception.class);
        assertThat(publishDraftTransaction.rollbackFor()).contains(Exception.class);
    }

    @Mock
    private ConfigService configService;

    @Mock
    private ApplicationSpaceRepository applicationSpaceRepository;

    @Mock
    private ExperimentApprovalVoteRepository experimentApprovalVoteRepository;

    @Mock
    private ExperimentApprovalEscalationRepository experimentApprovalEscalationRepository;

    @Mock
    private ApprovalEscalationNotificationDispatcher approvalEscalationNotificationDispatcher;

    @Mock
    private ApplicationDictionaryService applicationDictionaryService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ExperimentServiceImpl experimentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(experimentService, "trafficRuleEvaluator", new TrafficRuleEvaluator());
        ReflectionTestUtils.setField(experimentService, "groupConfigSchemaValidator",
                new GroupConfigSchemaValidator(new JsonUtil(new ObjectMapper())));
    }

    @AfterEach
    void tearDown() {
        ApiKeyContextHolder.clear();
    }

    @Test
    void createExperimentShouldInitializeConfigVersion() throws Exception {
        ExperimentCreateRequest request = buildRequest("创建实验");

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());

        assertThat(captor.getValue().getConfigVersion()).isEqualTo(1L);
    }

    @Test
    void createExperimentShouldPersistApiKeyOwnership() throws Exception {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        ExperimentCreateRequest request = buildRequest("应用归属实验");
        request.setAppId("spoofed-app");
        request.setOwner("spoofed-owner");

        Experiment experiment = experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        ExperimentMetadata metadata = captor.getValue();
        assertThat(experiment.getAppId()).isEqualTo("app-a");
        assertThat(experiment.getOwner()).isEqualTo("owner-a");
        assertThat(experiment.getCreator()).isEqualTo("owner-a");
        assertThat(metadata.getAppId()).isEqualTo("app-a");
        assertThat(metadata.getOwner()).isEqualTo("owner-a");
    }

    @Test
    void createExperimentShouldUseApplicationDefaultOwnerForAdminWhenOwnerMissing() throws Exception {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ExperimentCreateRequest request = buildRequest("默认负责人实验");
        request.setAppId("shop-app");
        when(applicationSpaceRepository.findByAppId("shop-app"))
                .thenReturn(Optional.of(applicationSpace("shop-app", "pm-a", 10)));

        Experiment experiment = experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(experiment.getOwner()).isEqualTo("pm-a");
        assertThat(captor.getValue().getOwner()).isEqualTo("pm-a");
    }

    @Test
    void createExperimentShouldMarkApprovalPendingWhenApplicationRequiresApproval() throws Exception {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ExperimentCreateRequest request = buildRequest("待审批实验");
        request.setAppId("shop-app");
        ApplicationSpace applicationSpace = applicationSpace("shop-app", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        applicationSpace.setApprovalPolicyVersion(7L);
        when(applicationSpaceRepository.findByAppId("shop-app"))
                .thenReturn(Optional.of(applicationSpace));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(captor.getValue().getApprovalOperator()).isEqualTo("ops");
        assertThat(captor.getValue().getApprovalOwnersSnapshot()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(captor.getValue().getApprovalRequiredCountSnapshot()).isEqualTo(2);
        assertThat(captor.getValue().getApprovalPolicyVersion()).isEqualTo(7L);
    }

    @Test
    void createExperimentShouldRejectWhenApplicationQuotaIsFull() throws Exception {
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ExperimentCreateRequest request = buildRequest("配额实验");
        request.setAppId("shop-app");
        when(applicationSpaceRepository.findByAppId("shop-app"))
                .thenReturn(Optional.of(applicationSpace("shop-app", "pm-a", 1)));
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_existing"));
        when(configService.getExperimentConfig("exp_existing")).thenReturn(metadataFor("exp_existing",
                "shop-app", "pm-a", Experiment.ExperimentStatus.DRAFT));

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("应用 shop-app 实验配额已满: 1");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createExperimentShouldPersistLayerId() throws Exception {
        ExperimentCreateRequest request = buildRequest("分层实验");
        request.setLayerId(" checkout-layer ");

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());

        assertThat(captor.getValue().getLayerId()).isEqualTo("checkout-layer");
    }

    @Test
    void createExperimentShouldReadApplicationDictionaryWithoutWritingIt() throws Exception {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        ExperimentCreateRequest request = buildRequest("字典同步实验");
        request.setAppId("spoofed-app");

        experimentService.createExperiment(request);

        verify(applicationDictionaryService).getApplicationDictionary("app-a");
    }

    @Test
    void createExperimentShouldRejectEventOutsideApplicationDictionary() throws Exception {
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        ExperimentCreateRequest request = buildRequest("非法字典选择实验");
        ApplicationEventDefinition applicationEvent = new ApplicationEventDefinition();
        applicationEvent.setKey("PRODUCT_VIEW");
        applicationEvent.setLabel("商品查看");
        ApplicationDictionaryResponse dictionary = new ApplicationDictionaryResponse();
        dictionary.setAppId("app-a");
        dictionary.setEventDefinitions(List.of(applicationEvent));
        dictionary.setMetricDefinitions(List.of());
        when(applicationDictionaryService.getApplicationDictionary("app-a")).thenReturn(dictionary);

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("事件不属于所选应用字典：PAY_SUCCESS");
        verify(configService, never()).saveExperimentConfig(any(), any());
    }

    @Test
    void updateExperimentShouldIncrementConfigVersion() throws Exception {
        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(3L);

        Experiment experiment = new Experiment();
        experiment.setId("exp_test_001");
        experiment.setName("旧实验");
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setCreateTime(LocalDateTime.now().minusDays(1));
        metadata.setExperiment(experiment);

        when(configService.getExperimentConfig("exp_test_001")).thenReturn(metadata);

        experimentService.updateExperiment("exp_test_001", buildRequest("新实验"));

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.eq("exp_test_001"), captor.capture());

        assertThat(captor.getValue().getConfigVersion()).isEqualTo(4L);
    }

    @Test
    void publishConfigVersionShouldPersistCurrentSnapshot() {
        ExperimentMetadata metadata = metadataFor("exp_publish", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setConfigVersion(4L);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_publish")).thenReturn(metadata);
        when(configService.saveExperimentConfigVersion(eq("exp_publish"), any(ExperimentMetadata.class),
                eq("owner-a"), eq("release v4"), isNull(), eq(ExperimentConfigVersion.SOURCE_TYPE_PUBLISH)))
                .thenReturn(versionFor(metadata, "owner-a", "release v4", null,
                        ExperimentConfigVersion.SOURCE_TYPE_PUBLISH));
        ExperimentConfigPublishRequest request = new ExperimentConfigPublishRequest();
        request.setComment(" release v4 ");

        ExperimentConfigVersionResponse response = experimentService.publishConfigVersion("exp_publish", request);

        assertThat(response.getExperimentId()).isEqualTo("exp_publish");
        assertThat(response.getConfigVersion()).isEqualTo(4L);
        assertThat(response.getPublishedBy()).isEqualTo("owner-a");
        assertThat(response.getPublishComment()).isEqualTo("release v4");
        assertThat(response.getSourceType()).isEqualTo(ExperimentConfigVersion.SOURCE_TYPE_PUBLISH);
    }

    @Test
    void saveConfigDraftShouldNotMutateCurrentRuntimeConfig() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_save", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        currentMetadata.setConfigVersion(7L);
        currentMetadata.getExperiment().setName("当前配置");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_save")).thenReturn(currentMetadata);
        when(configService.saveExperimentConfigDraft(eq("exp_draft_save"), any(ExperimentMetadata.class),
                eq(7L), eq("owner-a"), eq("draft change")))
                .thenAnswer(invocation -> draftFor(invocation.getArgument(1), 2L, 7L,
                        "owner-a", "draft change"));
        when(configService.saveExperimentConfigDraftApproval(any(ExperimentConfigDraftApproval.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ExperimentConfigDraftSaveRequest request = buildDraftRequest("草稿配置");
        request.setComment(" draft change ");

        ExperimentConfigDraftResponse response = experimentService.saveConfigDraft("exp_draft_save", request);

        assertThat(response.getBaseConfigVersion()).isEqualTo(7L);
        assertThat(response.getCurrentConfigVersion()).isEqualTo(7L);
        assertThat(response.getStale()).isFalse();
        assertThat(response.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.NOT_REQUIRED);
        assertThat(response.getDraftExperiment().getName()).isEqualTo("草稿配置");
        assertThat(currentMetadata.getExperiment().getName()).isEqualTo("当前配置");
        ArgumentCaptor<ExperimentConfigDraftApproval> approvalCaptor =
                ArgumentCaptor.forClass(ExperimentConfigDraftApproval.class);
        verify(configService).saveExperimentConfigDraftApproval(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getApprovalRequiredCountSnapshot()).isEqualTo(1);
        verify(configService, never()).saveExperimentConfig(eq("exp_draft_save"), any());
    }

    @Test
    void saveConfigDraftShouldMarkApprovalPendingWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_approval", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        currentMetadata.setConfigVersion(5L);
        currentMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        currentMetadata.setApprovalOperator("approver");
        currentMetadata.setApprovalComment("已通过");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_approval")).thenReturn(currentMetadata);
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        applicationSpace.setApprovalPolicyVersion(8L);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace));
        when(configService.saveExperimentConfigDraft(eq("exp_draft_approval"), any(ExperimentMetadata.class),
                eq(5L), eq("owner-a"), eq("draft approval")))
                .thenAnswer(invocation -> draftFor(invocation.getArgument(1), 2L, 5L,
                        "owner-a", "draft approval"));
        when(configService.saveExperimentConfigDraftApproval(any(ExperimentConfigDraftApproval.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ExperimentConfigDraftSaveRequest request = buildDraftRequest("待审批草稿");
        request.setComment(" draft approval ");

        ExperimentConfigDraftResponse response = experimentService.saveConfigDraft("exp_draft_approval", request);

        ArgumentCaptor<ExperimentMetadata> currentCaptor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_draft_approval"), currentCaptor.capture());
        ExperimentMetadata savedCurrentMetadata = currentCaptor.getValue();
        assertThat(savedCurrentMetadata.getConfigVersion()).isEqualTo(5L);
        assertThat(savedCurrentMetadata.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(savedCurrentMetadata.getApprovalOperator()).isEqualTo("owner-a");
        assertThat(savedCurrentMetadata.getApprovalComment()).isEqualTo("配置草稿保存后等待发布审批");
        assertThat(savedCurrentMetadata.getApprovalOwnersSnapshot()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(savedCurrentMetadata.getApprovalRequiredCountSnapshot()).isEqualTo(2);
        assertThat(savedCurrentMetadata.getApprovalPolicyVersion()).isEqualTo(8L);
        assertThat(response.getDraftExperiment().getApprovalStatus())
                .isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(response.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        ArgumentCaptor<ExperimentConfigDraftApproval> approvalCaptor =
                ArgumentCaptor.forClass(ExperimentConfigDraftApproval.class);
        verify(configService).saveExperimentConfigDraftApproval(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getDraftVersion()).isEqualTo(2L);
        assertThat(approvalCaptor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(approvalCaptor.getValue().getApprovalOwnersSnapshot()).containsExactly("reviewer-a", "reviewer-b");
        assertThat(approvalCaptor.getValue().getApprovalRequiredCountSnapshot()).isEqualTo(2);
        assertThat(approvalCaptor.getValue().getApprovalPolicyVersion()).isEqualTo(8L);
    }

    @Test
    void publishConfigDraftShouldApplyDraftAsNewRuntimeVersion() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_publish", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        currentMetadata.setConfigVersion(7L);
        currentMetadata.getExperiment().setName("当前配置");
        ExperimentMetadata draftMetadata = metadataFor("exp_draft_publish", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        draftMetadata.setConfigVersion(7L);
        draftMetadata.getExperiment().setName("草稿配置");
        ExperimentConfigDraft draft = draftFor(draftMetadata, 3L, 7L, "owner-a", "draft ready");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_publish")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigDraft("exp_draft_publish")).thenReturn(Optional.of(draft));
        when(configService.saveExperimentConfigVersion(eq("exp_draft_publish"), any(ExperimentMetadata.class),
                eq("owner-a"), eq("publish draft"), eq(7L),
                eq(ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH)))
                .thenAnswer(invocation -> versionFor(invocation.getArgument(1), "owner-a", "publish draft",
                        7L, ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH));
        ExperimentConfigPublishRequest request = new ExperimentConfigPublishRequest();
        request.setComment(" publish draft ");

        ExperimentConfigVersionResponse response = experimentService.publishConfigDraft("exp_draft_publish", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_draft_publish"), captor.capture());
        verify(configService).deleteExperimentConfigDraft("exp_draft_publish");
        ExperimentMetadata publishedMetadata = captor.getValue();
        assertThat(publishedMetadata.getConfigVersion()).isEqualTo(8L);
        assertThat(publishedMetadata.getExperiment().getName()).isEqualTo("草稿配置");
        assertThat(publishedMetadata.getExperiment().getStatus()).isEqualTo(Experiment.ExperimentStatus.PAUSED);
        assertThat(response.getConfigVersion()).isEqualTo(8L);
        assertThat(response.getSourceConfigVersion()).isEqualTo(7L);
        assertThat(response.getSourceType()).isEqualTo(ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH);
    }

    @Test
    void publishConfigDraftShouldRejectPendingApprovalWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_pending_approval", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        currentMetadata.setConfigVersion(7L);
        currentMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ExperimentMetadata draftMetadata = metadataFor("exp_draft_pending_approval", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        draftMetadata.setConfigVersion(7L);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_pending_approval")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigDraft("exp_draft_pending_approval"))
                .thenReturn(Optional.of(draftFor(draftMetadata, 3L, 7L, "owner-a", "draft ready")));
        when(configService.getExperimentConfigDraftApproval("exp_draft_pending_approval", 3L))
                .thenReturn(Optional.of(draftApprovalFor("exp_draft_pending_approval", 3L, 7L,
                        ExperimentMetadata.ApprovalStatus.PENDING)));
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));

        assertThatThrownBy(() -> experimentService.publishConfigDraft("exp_draft_pending_approval", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.EXPERIMENT_STATUS_ERROR))
                .hasMessageContaining("配置草稿发布前需要审批通过");
        verify(configService, never()).saveExperimentConfig(eq("exp_draft_pending_approval"), any());
        verify(configService, never()).deleteExperimentConfigDraft("exp_draft_pending_approval");
    }

    @Test
    void publishConfigDraftShouldPreserveApprovedStatusWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_approved", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        currentMetadata.setConfigVersion(7L);
        currentMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        currentMetadata.setApprovalOperator("approver");
        currentMetadata.setApprovalComment("同意发布草稿");
        currentMetadata.setApprovalUpdatedAt(LocalDateTime.now().minusMinutes(5));
        ExperimentMetadata draftMetadata = metadataFor("exp_draft_approved", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        draftMetadata.setConfigVersion(7L);
        draftMetadata.getExperiment().setName("已审批草稿");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_approved")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigDraft("exp_draft_approved"))
                .thenReturn(Optional.of(draftFor(draftMetadata, 3L, 7L, "owner-a", "draft ready")));
        when(configService.getExperimentConfigDraftApproval("exp_draft_approved", 3L))
                .thenReturn(Optional.of(draftApprovalFor("exp_draft_approved", 3L, 7L,
                        ExperimentMetadata.ApprovalStatus.APPROVED)));
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));
        when(configService.saveExperimentConfigVersion(eq("exp_draft_approved"), any(ExperimentMetadata.class),
                eq("owner-a"), eq("publish approved draft"), eq(7L),
                eq(ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH)))
                .thenAnswer(invocation -> versionFor(invocation.getArgument(1), "owner-a",
                        "publish approved draft", 7L, ExperimentConfigVersion.SOURCE_TYPE_DRAFT_PUBLISH));
        ExperimentConfigPublishRequest request = new ExperimentConfigPublishRequest();
        request.setComment(" publish approved draft ");

        experimentService.publishConfigDraft("exp_draft_approved", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_draft_approved"), captor.capture());
        ExperimentMetadata publishedMetadata = captor.getValue();
        assertThat(publishedMetadata.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(publishedMetadata.getApprovalOperator()).isEqualTo("approver");
        assertThat(publishedMetadata.getApprovalComment()).isEqualTo("同意发布草稿");
        assertThat(publishedMetadata.getExperiment().getName()).isEqualTo("已审批草稿");
    }

    @Test
    void publishConfigDraftShouldRejectStaleDraft() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_stale_draft", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        currentMetadata.setConfigVersion(8L);
        ExperimentMetadata draftMetadata = metadataFor("exp_stale_draft", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        draftMetadata.setConfigVersion(7L);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_stale_draft")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigDraft("exp_stale_draft"))
                .thenReturn(Optional.of(draftFor(draftMetadata, 2L, 7L, "owner-a", null)));

        assertThatThrownBy(() -> experimentService.publishConfigDraft("exp_stale_draft", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT));
        verify(configService, never()).saveExperimentConfig(eq("exp_stale_draft"), any());
        verify(configService, never()).deleteExperimentConfigDraft("exp_stale_draft");
    }

    @Test
    void publishConfigDraftShouldRejectOutsideApplicationReleaseWindow() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_draft_release_window", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        currentMetadata.setConfigVersion(7L);
        ExperimentMetadata draftMetadata = metadataFor("exp_draft_release_window", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        draftMetadata.setConfigVersion(7L);
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, false);
        applicationSpace.setReleaseWindowEnabled(true);
        applicationSpace.setReleaseWindowTimezone("Asia/Shanghai");
        applicationSpace.setReleaseWindowDays(List.of(1, 2, 3, 4, 5));
        applicationSpace.setReleaseWindowStartTime("09:00");
        applicationSpace.setReleaseWindowEndTime("18:00");
        ReflectionTestUtils.setField(experimentService, "releaseWindowClock",
                Clock.fixed(Instant.parse("2026-07-18T02:00:00Z"), ZoneId.of("UTC")));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_release_window")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigDraft("exp_draft_release_window"))
                .thenReturn(Optional.of(draftFor(draftMetadata, 3L, 7L, "owner-a", "draft ready")));
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));

        assertThatThrownBy(() -> experimentService.publishConfigDraft("exp_draft_release_window", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发布配置草稿不在应用发布窗口内");
        verify(configService, never()).saveExperimentConfig(eq("exp_draft_release_window"), any());
        verify(configService, never()).deleteExperimentConfigDraft("exp_draft_release_window");
    }

    @Test
    void rollbackConfigVersionShouldCreateNewVersionFromPublishedSnapshot() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_rollback", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        currentMetadata.setConfigVersion(4L);
        currentMetadata.getExperiment().setName("当前配置");
        currentMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        currentMetadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED);
        currentMetadata.setConclusionConfigVersion(4L);
        currentMetadata.setConclusionReportSnapshotVersion(9);
        currentMetadata.setConclusionOperator("analyst-a");
        currentMetadata.setConclusionComment("确认毕业");
        ExperimentMetadata targetMetadata = metadataFor("exp_rollback", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        targetMetadata.setConfigVersion(2L);
        targetMetadata.getExperiment().setName("历史配置");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_rollback")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigVersion("exp_rollback", 2L))
                .thenReturn(Optional.of(versionFor(targetMetadata, "owner-a", "release v2", null,
                        ExperimentConfigVersion.SOURCE_TYPE_PUBLISH)));
        when(configService.saveExperimentConfigVersion(eq("exp_rollback"), any(ExperimentMetadata.class),
                eq("owner-a"), eq("rollback to v2"), eq(2L), eq(ExperimentConfigVersion.SOURCE_TYPE_ROLLBACK)))
                .thenAnswer(invocation -> versionFor(invocation.getArgument(1), "owner-a", "rollback to v2",
                        2L, ExperimentConfigVersion.SOURCE_TYPE_ROLLBACK));
        ExperimentConfigRollbackRequest request = new ExperimentConfigRollbackRequest();
        request.setTargetConfigVersion(2L);
        request.setComment(" rollback to v2 ");

        ExperimentConfigVersionResponse response = experimentService.rollbackConfigVersion("exp_rollback", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_rollback"), captor.capture());
        ExperimentMetadata rollbackMetadata = captor.getValue();
        assertThat(rollbackMetadata.getConfigVersion()).isEqualTo(5L);
        assertThat(rollbackMetadata.getExperiment().getName()).isEqualTo("历史配置");
        assertThat(rollbackMetadata.getExperiment().getStatus()).isEqualTo(Experiment.ExperimentStatus.RUNNING);
        assertThat(rollbackMetadata.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(rollbackMetadata.getConclusionStatus()).isEqualTo(ExperimentMetadata.ConclusionStatus.NOT_READY);
        assertThat(rollbackMetadata.getConclusionConfigVersion()).isNull();
        assertThat(rollbackMetadata.getConclusionReportSnapshotVersion()).isNull();
        assertThat(rollbackMetadata.getConclusionOperator()).isNull();
        assertThat(rollbackMetadata.getConclusionComment()).isNull();
        assertThat(response.getConfigVersion()).isEqualTo(5L);
        assertThat(response.getSourceConfigVersion()).isEqualTo(2L);
        assertThat(response.getSourceType()).isEqualTo(ExperimentConfigVersion.SOURCE_TYPE_ROLLBACK);
    }

    @Test
    void rollbackConfigVersionShouldRejectMissingTargetVersion() throws Exception {
        ExperimentMetadata currentMetadata = metadataFor("exp_missing_version", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        currentMetadata.setConfigVersion(4L);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_missing_version")).thenReturn(currentMetadata);
        when(configService.getExperimentConfigVersion("exp_missing_version", 2L)).thenReturn(Optional.empty());
        ExperimentConfigRollbackRequest request = new ExperimentConfigRollbackRequest();
        request.setTargetConfigVersion(2L);

        assertThatThrownBy(() -> experimentService.rollbackConfigVersion("exp_missing_version", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.DATA_NOT_FOUND));
        verify(configService, never()).saveExperimentConfig(eq("exp_missing_version"), any());
    }

    @Test
    void updateConclusionStatusShouldBindConfigAndReportSnapshotEvidence() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_ready", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(7L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_ready", 3, true, false, List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        snapshot.setPrimaryMetricKey("pay_rate");
        snapshot.setBestPerformingGroup("variant");
        when(configService.getExperimentConfig("exp_conclusion_ready")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_ready")).thenReturn(List.of(snapshot));
        ApiKeyContextHolder.set(principal("app-a", "analyst-a", ApiKeyScope.MANAGEMENT));
        ExperimentConclusionStatusUpdateRequest request = new ExperimentConclusionStatusUpdateRequest();
        request.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW.name());
        request.setExpectedConfigVersion(7L);
        request.setReportSnapshotVersion(3);

        experimentService.updateConclusionStatus("exp_conclusion_ready", request);

        ArgumentCaptor<ExperimentMetadata> metadataCaptor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_conclusion_ready"), metadataCaptor.capture());
        ExperimentMetadata savedMetadata = metadataCaptor.getValue();
        assertThat(savedMetadata.getConclusionStatus())
                .isEqualTo(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        assertThat(savedMetadata.getConclusionConfigVersion()).isEqualTo(7L);
        assertThat(savedMetadata.getConclusionReportSnapshotVersion()).isEqualTo(3);
        assertThat(savedMetadata.getConclusionOperator()).isEqualTo("analyst-a");
        assertThat(savedMetadata.getConclusionComment()).isNull();

        ArgumentCaptor<AuditLogRecord> auditCaptor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).record(auditCaptor.capture());
        Map<String, Object> detail = auditCaptor.getValue().getDetail();
        assertThat(detail)
                .containsEntry("conclusionConfigVersion", 7L)
                .containsEntry("conclusionReportSnapshotVersion", 3)
                .containsEntry("latestReportSnapshotVersion", 3)
                .containsEntry("latestReportConclusionStatus",
                        ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW.name())
                .containsEntry("latestReportAnalysisReady", true)
                .containsEntry("latestReportPrimaryMetricKey", "pay_rate")
                .containsEntry("latestReportBestPerformingGroup", "variant");
    }

    @Test
    void updateConclusionStatusShouldRejectStaleConfigVersionEvidence() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_stale_config", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        when(configService.getExperimentConfig("exp_conclusion_stale_config")).thenReturn(metadata);
        ExperimentConclusionStatusUpdateRequest request = new ExperimentConclusionStatusUpdateRequest();
        request.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW.name());
        request.setExpectedConfigVersion(7L);
        request.setReportSnapshotVersion(3);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_stale_config", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT))
                .hasMessageContaining("人工结论基线配置版本已过期");
        verify(analysisService, never()).listReportSnapshots("exp_conclusion_stale_config");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_stale_config"), any());
    }

    @Test
    void updateConclusionStatusShouldRequireConfigVersionEvidence() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_missing_config_version", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        when(configService.getExperimentConfig("exp_conclusion_missing_config_version")).thenReturn(metadata);
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW, null, 3, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus(
                "exp_conclusion_missing_config_version", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.VALIDATION_ERROR))
                .hasMessageContaining("确认实验结论必须提交当前配置版本");
        verify(analysisService, never()).listReportSnapshots("exp_conclusion_missing_config_version");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_missing_config_version"), any());
    }

    @Test
    void updateConclusionStatusShouldRequireReportSnapshotVersionEvidence() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_missing_snapshot_version", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        when(configService.getExperimentConfig("exp_conclusion_missing_snapshot_version")).thenReturn(metadata);
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW, 8L, null, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus(
                "exp_conclusion_missing_snapshot_version", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.VALIDATION_ERROR))
                .hasMessageContaining("确认实验结论必须提交报告快照版本");
        verify(analysisService, never()).listReportSnapshots("exp_conclusion_missing_snapshot_version");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_missing_snapshot_version"), any());
    }

    @Test
    void updateConclusionStatusShouldRequireExistingReportSnapshot() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_no_snapshot", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        when(configService.getExperimentConfig("exp_conclusion_no_snapshot")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_no_snapshot")).thenReturn(List.of());
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW, 8L, 3, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_no_snapshot", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.EXPERIMENT_STATUS_ERROR))
                .hasMessageContaining("确认实验结论前需要先生成报告快照");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_no_snapshot"), any());
    }

    @Test
    void updateConclusionStatusShouldRejectStaleReportSnapshotVersionEvidence() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_stale_snapshot", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_stale_snapshot", 4, true, false,
                List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        when(configService.getExperimentConfig("exp_conclusion_stale_snapshot")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_stale_snapshot")).thenReturn(List.of(snapshot));
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW, 8L, 3, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_stale_snapshot", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT))
                .hasMessageContaining("人工结论报告快照已过期");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_stale_snapshot"), any());
    }

    @Test
    void updateConclusionStatusShouldRejectReportSnapshotBeforeAnalysisReady() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_unready_snapshot", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_unready_snapshot", 4, false, false,
                List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        when(configService.getExperimentConfig("exp_conclusion_unready_snapshot")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_unready_snapshot")).thenReturn(List.of(snapshot));
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW, 8L, 4, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_unready_snapshot", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.EXPERIMENT_STATUS_ERROR))
                .hasMessageContaining("最新报告尚未满足分析门禁");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_unready_snapshot"), any());
    }

    @Test
    void updateConclusionStatusShouldRejectGraduationWhenLatestSnapshotHasSrm() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_srm", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_srm", 4, true, true, List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED);
        when(configService.getExperimentConfig("exp_conclusion_srm")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_srm")).thenReturn(List.of(snapshot));
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.GRADUATED, 8L, 4, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_srm", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.EXPERIMENT_STATUS_ERROR))
                .hasMessageContaining("最新报告存在 SRM");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_srm"), any());
    }

    @Test
    void updateConclusionStatusShouldRejectGraduationWhenLatestSnapshotHasBreachedGuardrails() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_guardrail", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(8L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_guardrail", 4, true, false,
                List.of("CONSULT_RATE"));
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED);
        when(configService.getExperimentConfig("exp_conclusion_guardrail")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_guardrail")).thenReturn(List.of(snapshot));
        ExperimentConclusionStatusUpdateRequest request = conclusionStatusRequest(
                ExperimentMetadata.ConclusionStatus.GRADUATED, 8L, 4, null);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_guardrail", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.EXPERIMENT_STATUS_ERROR))
                .hasMessageContaining("最新报告存在护栏异常");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_guardrail"), any());
    }

    @Test
    void updateConclusionStatusShouldRequireCommentWhenReportSuggestionDiffers() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_mismatch", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(7L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_mismatch", 4, true, false, List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        when(configService.getExperimentConfig("exp_conclusion_mismatch")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_mismatch")).thenReturn(List.of(snapshot));
        ExperimentConclusionStatusUpdateRequest request = new ExperimentConclusionStatusUpdateRequest();
        request.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED.name());
        request.setExpectedConfigVersion(7L);
        request.setReportSnapshotVersion(4);

        assertThatThrownBy(() -> experimentService.updateConclusionStatus("exp_conclusion_mismatch", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.VALIDATION_ERROR))
                .hasMessageContaining("人工结论与最新报告建议不一致");
        verify(configService, never()).saveExperimentConfig(eq("exp_conclusion_mismatch"), any());
    }

    @Test
    void updateConclusionStatusShouldAllowTerminalConclusionWithManualRationale() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion_graduate", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setConfigVersion(7L);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_conclusion_graduate", 4, true, false, List.of());
        snapshot.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        when(configService.getExperimentConfig("exp_conclusion_graduate")).thenReturn(metadata);
        when(analysisService.listReportSnapshots("exp_conclusion_graduate")).thenReturn(List.of(snapshot));
        ApiKeyContextHolder.set(principal("app-a", "analyst-a", ApiKeyScope.MANAGEMENT));
        ExperimentConclusionStatusUpdateRequest request = new ExperimentConclusionStatusUpdateRequest();
        request.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED.name());
        request.setExpectedConfigVersion(7L);
        request.setReportSnapshotVersion(4);
        request.setComment("主指标胜出且护栏通过，确认毕业");

        experimentService.updateConclusionStatus("exp_conclusion_graduate", request);

        ArgumentCaptor<ExperimentMetadata> metadataCaptor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_conclusion_graduate"), metadataCaptor.capture());
        ExperimentMetadata savedMetadata = metadataCaptor.getValue();
        assertThat(savedMetadata.getConclusionStatus()).isEqualTo(ExperimentMetadata.ConclusionStatus.GRADUATED);
        assertThat(savedMetadata.getConclusionConfigVersion()).isEqualTo(7L);
        assertThat(savedMetadata.getConclusionReportSnapshotVersion()).isEqualTo(4);
        assertThat(savedMetadata.getConclusionOperator()).isEqualTo("analyst-a");
        assertThat(savedMetadata.getConclusionComment()).isEqualTo("主指标胜出且护栏通过，确认毕业");
    }

    @Test
    void updateExperimentShouldReadApplicationDictionaryWithoutWritingIt() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_dict", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setConfigVersion(1L);
        when(configService.getExperimentConfig("exp_dict")).thenReturn(metadata);

        experimentService.updateExperiment("exp_dict", buildRequest("更新字典实验"));

        verify(applicationDictionaryService).getApplicationDictionary("app-a");
    }

    @Test
    void createExperimentShouldPersistGroupConfigSchemaAndNormalizedDefaults() throws Exception {
        ExperimentCreateRequest request = buildRequest("配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, "默认主标题"),
                schemaField("badgeCount", "角标数量", GroupConfigFieldDefinition.ValueType.INTEGER, false, 2),
                schemaField("showQualityBadge", "展示质检标签", GroupConfigFieldDefinition.ValueType.BOOLEAN, false, true),
                schemaField("extraMeta", "附加信息", GroupConfigFieldDefinition.ValueType.JSON, false, "{\"scene\":\"detail\"}")
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of(
                "mainTitle", "实验标题",
                "badgeCount", "3",
                "showQualityBadge", "false"
        )));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());

        ExperimentMetadata metadata = captor.getValue();
        assertThat(metadata.getGroupConfigSchema()).hasSize(4);
        assertThat(metadata.getGroups().get("A").getConfig())
                .containsEntry("mainTitle", "基准标题")
                .containsEntry("badgeCount", 2)
                .containsEntry("showQualityBadge", true);
        assertThat(metadata.getGroups().get("B").getConfig())
                .containsEntry("mainTitle", "实验标题")
                .containsEntry("badgeCount", 3)
                .containsEntry("showQualityBadge", false);
        assertThat(metadata.getGroups().get("A").getConfig().get("extraMeta"))
                .isInstanceOf(Map.class);
    }

    @Test
    void createExperimentShouldRejectInvalidSchemaTypedValue() {
        ExperimentCreateRequest request = buildRequest("非法配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("badgeCount", "角标数量", GroupConfigFieldDefinition.ValueType.INTEGER, true, null)
        ));
        request.getGroups().get(0).setConfig(Map.of("badgeCount", "abc"));
        request.getGroups().get(1).setConfig(Map.of("badgeCount", 2));

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("badgeCount")
                .hasMessageContaining("INTEGER");
    }

    @Test
    void createExperimentShouldAllowNullSchemaDefaultValue() throws Exception {
        ExperimentCreateRequest request = buildRequest("空默认值配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, false, null)
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "实验标题")));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().getGroupConfigSchema().get(0).getDefaultValue()).isNull();
        assertThat(captor.getValue().getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
    }

    @Test
    void createExperimentShouldAllowManualSchemaBelowAiGenerationMinimum() throws Exception {
        ExperimentCreateRequest request = buildRequest("手动保存精简配置实验");
        request.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, null),
                schemaField("subtitle", "副标题", GroupConfigFieldDefinition.ValueType.STRING, false, null)
        ));
        request.getGroups().get(0).setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));
        request.getGroups().get(1).setConfig(new LinkedHashMap<>(Map.of(
                "mainTitle", "实验标题",
                "subtitle", "平台补贴"
        )));

        experimentService.createExperiment(request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        assertThat(captor.getValue().getGroupConfigSchema()).hasSize(2);
        assertThat(captor.getValue().getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
        assertThat(captor.getValue().getGroups().get("B").getConfig())
                .containsEntry("mainTitle", "实验标题")
                .containsEntry("subtitle", "平台补贴");
    }

    @Test
    void createExperimentShouldRejectMissingEventDefinitions() {
        ExperimentCreateRequest request = buildRequest("缺少事件定义");
        request.setEventDefinitions(null);

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("至少需要定义一个事件");
    }

    @Test
    void createExperimentShouldRejectMetricReferencingUndefinedEvent() {
        ExperimentCreateRequest request = buildRequest("非法指标事件");
        request.getMetricDefinitions().get(0).setNumeratorEventType("ORDER_SUBMITTED");

        assertThatThrownBy(() -> experimentService.createExperiment(request))
                .isInstanceOf(com.pisces.service.exception.BusinessException.class)
                .hasMessageContaining("ORDER_SUBMITTED")
                .hasMessageContaining("事件定义");
    }

    @Test
    void shouldNotKeepSingleUseSafeWrapperMethods() {
        List<String> methodNames = Arrays.stream(ExperimentServiceImpl.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .doesNotContain("pauseExperimentSafe")
                .doesNotContain("stopExperimentSafe")
                .doesNotContain("resumeExperimentSafe")
                .doesNotContain("deleteExperimentSafe");
    }

    @Test
    void getExperimentShouldExposeGroupConfigSchema() {
        Experiment experiment = new Experiment();
        experiment.setId("exp_schema_001");
        experiment.setName("配置实验");
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);

        com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
        group.setId("A");
        group.setName("基准组");
        group.setTrafficRatio(0.5);
        group.setConfig(new LinkedHashMap<>(Map.of("mainTitle", "基准标题")));

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setLayerId("detail-page");
        metadata.setExperiment(experiment);
        metadata.setGroups(new LinkedHashMap<>(Map.of("A", group)));
        metadata.setGroupConfigSchema(List.of(
                schemaField("mainTitle", "主标题", GroupConfigFieldDefinition.ValueType.STRING, true, null)
        ));
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        metadata.setConclusionConfigVersion(7L);
        metadata.setConclusionReportSnapshotVersion(3);
        metadata.setConclusionOperator("analyst-a");
        metadata.setConclusionComment("等待业务确认");

        when(configService.getExperimentConfig("exp_schema_001")).thenReturn(metadata);
        metadata.setEventDefinitions(List.of(eventDefinition("PRODUCT_VIEW", "商品查看", true)));
        metadata.setMetricDefinitions(List.of(metricDefinition("PAY_RATE", "支付率",
                "PRODUCT_VIEW", "PRODUCT_VIEW", true, false)));

        ExperimentResponse response = experimentService.getExperiment("exp_schema_001");

        assertThat(response.getEventDefinitions()).hasSize(1);
        assertThat(response.getLayerId()).isEqualTo("detail-page");
        assertThat(response.getEventDefinitions().get(0).getKey()).isEqualTo("PRODUCT_VIEW");
        assertThat(response.getMetricDefinitions()).hasSize(1);
        assertThat(response.getMetricDefinitions().get(0).getKey()).isEqualTo("PAY_RATE");
        assertThat(response.getGroupConfigSchema()).hasSize(1);
        assertThat(response.getGroupConfigSchema().get(0).getKey()).isEqualTo("mainTitle");
        assertThat(response.getConclusionStatus()).isEqualTo(ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW);
        assertThat(response.getConclusionConfigVersion()).isEqualTo(7L);
        assertThat(response.getConclusionReportSnapshotVersion()).isEqualTo(3);
        assertThat(response.getConclusionOperator()).isEqualTo("analyst-a");
        assertThat(response.getConclusionComment()).isEqualTo("等待业务确认");
        assertThat(response.getGroups().get("A").getConfig()).containsEntry("mainTitle", "基准标题");
    }

    @Test
    void getExperimentShouldRejectDifferentApp() {
        ExperimentMetadata metadata = metadataFor("exp_app_a", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        when(configService.getExperimentConfig("exp_app_a")).thenReturn(metadata);
        ApiKeyContextHolder.set(principal("app-b", "owner-b", ApiKeyScope.MANAGEMENT));

        assertThatThrownBy(() -> experimentService.getExperiment("exp_app_a"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.FORBIDDEN));
    }

    @Test
    void listExperimentsShouldFilterDifferentApps() throws Exception {
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_app_a", "exp_app_b"));
        when(configService.getExperimentConfig("exp_app_a")).thenReturn(metadataFor("exp_app_a", "app-a",
                "owner-a", Experiment.ExperimentStatus.DRAFT));
        when(configService.getExperimentConfig("exp_app_b")).thenReturn(metadataFor("exp_app_b", "app-b",
                "owner-b", Experiment.ExperimentStatus.RUNNING));
        ApiKeyContextHolder.set(principal("app-b", "owner-b", ApiKeyScope.MANAGEMENT));

        List<Experiment> experiments = experimentService.listExperiments();

        assertThat(experiments)
                .extracting(Experiment::getId)
                .containsExactly("exp_app_b");
    }

    @Test
    void listExperimentsShouldIncludeConclusionStatus() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_conclusion", "app-a", "owner-a",
                Experiment.ExperimentStatus.STOPPED);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.GRADUATED);
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_conclusion"));
        when(configService.getExperimentConfig("exp_conclusion")).thenReturn(metadata);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<Experiment> experiments = experimentService.listExperiments();

        assertThat(experiments).singleElement()
                .extracting(Experiment::getConclusionStatus)
                .isEqualTo(ExperimentMetadata.ConclusionStatus.GRADUATED);
    }

    @Test
    void listExperimentsShouldFilterOwnerWithinVisibleApp() throws Exception {
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_owner_a", "exp_owner_b", "exp_other_app"));
        when(configService.getExperimentConfig("exp_owner_a")).thenReturn(metadataFor("exp_owner_a", "app-a",
                "owner-a", Experiment.ExperimentStatus.DRAFT));
        when(configService.getExperimentConfig("exp_owner_b")).thenReturn(metadataFor("exp_owner_b", "app-a",
                "owner-b", Experiment.ExperimentStatus.RUNNING));
        when(configService.getExperimentConfig("exp_other_app")).thenReturn(metadataFor("exp_other_app", "app-b",
                "owner-b", Experiment.ExperimentStatus.RUNNING));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<Experiment> experiments = experimentService.listExperiments(null, List.of(), null, "owner-b");

        assertThat(experiments)
                .extracting(Experiment::getId)
                .containsExactly("exp_owner_b");
    }

    @Test
    void listExperimentsShouldAllowAdminFilterByAppAndStatuses() throws Exception {
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_app_a", "exp_app_b", "exp_app_b_draft"));
        when(configService.getExperimentConfig("exp_app_a")).thenReturn(metadataFor("exp_app_a", "app-a",
                "owner-a", Experiment.ExperimentStatus.RUNNING));
        when(configService.getExperimentConfig("exp_app_b")).thenReturn(metadataFor("exp_app_b", "app-b",
                "owner-b", Experiment.ExperimentStatus.RUNNING));
        when(configService.getExperimentConfig("exp_app_b_draft")).thenReturn(metadataFor("exp_app_b_draft", "app-b",
                "owner-b", Experiment.ExperimentStatus.DRAFT));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<Experiment> experiments = experimentService.listExperiments(null, List.of("RUNNING"), "app-b", null);

        assertThat(experiments)
                .extracting(Experiment::getId)
                .containsExactly("exp_app_b");
    }

    @Test
    void listExperimentsShouldReturnEmptyWhenNonAdminFiltersOtherApp() throws Exception {
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_app_a", "exp_app_b"));
        when(configService.getExperimentConfig("exp_app_a")).thenReturn(metadataFor("exp_app_a", "app-a",
                "owner-a", Experiment.ExperimentStatus.DRAFT));
        when(configService.getExperimentConfig("exp_app_b")).thenReturn(metadataFor("exp_app_b", "app-b",
                "owner-b", Experiment.ExperimentStatus.RUNNING));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<Experiment> experiments = experimentService.listExperiments(null, List.of(), "app-b", null);

        assertThat(experiments).isEmpty();
    }

    @Test
    void listExperimentsShouldRejectInvalidStatus() {
        assertThatThrownBy(() -> experimentService.listExperiments("UNKNOWN", List.of(), null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.VALIDATION_ERROR));
    }

    @Test
    void listApprovalTasksShouldReturnPendingVisibleTasks() throws Exception {
        ExperimentMetadata pendingMetadata = metadataFor("exp_pending", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        pendingMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        pendingMetadata.setApprovalOperator("owner-a");
        pendingMetadata.setApprovalComment("等待审批");
        pendingMetadata.setApprovalUpdatedAt(LocalDateTime.now().minusHours(1));
        ExperimentMetadata approvedMetadata = metadataFor("exp_approved", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        approvedMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        approvedMetadata.setApprovalUpdatedAt(LocalDateTime.now());
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_pending", "exp_approved"));
        when(configService.getExperimentConfig("exp_pending")).thenReturn(pendingMetadata);
        when(configService.getExperimentConfig("exp_approved")).thenReturn(approvedMetadata);
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_pending",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(approvalVoteFor("exp_pending", ExperimentApprovalTaskType.EXPERIMENT_START,
                        0L, ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a")));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks("app-a", null, null);

        assertThat(approvalTasks).hasSize(1);
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getExperimentId()).isEqualTo("exp_pending");
        assertThat(approvalTask.getAppId()).isEqualTo("app-a");
        assertThat(approvalTask.getOwner()).isEqualTo("owner-a");
        assertThat(approvalTask.getApprovalType()).isEqualTo(ExperimentApprovalTaskType.EXPERIMENT_START);
        assertThat(approvalTask.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(approvalTask.getApprovalComment()).isEqualTo("等待审批");
        assertThat(approvalTask.getApprovalRequiredCount()).isEqualTo(2);
        assertThat(approvalTask.getApprovalApprovedCount()).isEqualTo(1);
        assertThat(approvalTask.getApprovalRejectedCount()).isZero();
        assertThat(approvalTask.getApprovalProgressText()).isEqualTo("审批进度 1/2");
    }

    @Test
    void listApprovalTasksShouldPreferCurrentDraftApprovalRecord() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_draft_task", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalUpdatedAt(LocalDateTime.now().minusHours(1));
        ExperimentConfigDraftApproval draftApproval = draftApprovalFor("exp_draft_task", 4L, 9L,
                ExperimentMetadata.ApprovalStatus.PENDING);
        draftApproval.setDraftComment("调整实验组配置");
        draftApproval.setApprovalComment("等待草稿审批");
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_draft_task"));
        when(configService.getExperimentConfig("exp_draft_task")).thenReturn(metadata);
        when(configService.getCurrentExperimentConfigDraftApproval("exp_draft_task"))
                .thenReturn(Optional.of(draftApproval));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks("app-a", null, null);

        assertThat(approvalTasks).hasSize(1);
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getApprovalType()).isEqualTo(ExperimentApprovalTaskType.CONFIG_DRAFT);
        assertThat(approvalTask.getDraftVersion()).isEqualTo(4L);
        assertThat(approvalTask.getBaseConfigVersion()).isEqualTo(9L);
        assertThat(approvalTask.getDraftComment()).isEqualTo("调整实验组配置");
        assertThat(approvalTask.getApprovalComment()).isEqualTo("等待草稿审批");
    }

    @Test
    void listConfigDraftApprovalsShouldReturnDraftApprovalHistory() {
        ExperimentMetadata metadata = metadataFor("exp_draft_history", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        ExperimentConfigDraftApproval latestApproval = draftApprovalFor("exp_draft_history", 4L, 9L,
                ExperimentMetadata.ApprovalStatus.APPROVED);
        latestApproval.setRequestedBy("owner-a");
        latestApproval.setDraftComment("调整文案配置");
        latestApproval.setApprovalOperator("reviewer-a");
        latestApproval.setApprovalComment("通过");
        ExperimentConfigDraftApproval previousApproval = draftApprovalFor("exp_draft_history", 3L, 8L,
                ExperimentMetadata.ApprovalStatus.REJECTED);
        when(configService.getExperimentConfig("exp_draft_history")).thenReturn(metadata);
        when(configService.listExperimentConfigDraftApprovals("exp_draft_history"))
                .thenReturn(List.of(latestApproval, previousApproval));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<ExperimentConfigDraftApprovalResponse> approvals =
                experimentService.listConfigDraftApprovals("exp_draft_history");

        assertThat(approvals).hasSize(2);
        ExperimentConfigDraftApprovalResponse latestResponse = approvals.get(0);
        assertThat(latestResponse.getExperimentId()).isEqualTo("exp_draft_history");
        assertThat(latestResponse.getDraftVersion()).isEqualTo(4L);
        assertThat(latestResponse.getBaseConfigVersion()).isEqualTo(9L);
        assertThat(latestResponse.getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(latestResponse.getRequestedBy()).isEqualTo("owner-a");
        assertThat(latestResponse.getDraftComment()).isEqualTo("调整文案配置");
        assertThat(latestResponse.getApprovalOperator()).isEqualTo("reviewer-a");
        assertThat(latestResponse.getApprovalComment()).isEqualTo("通过");
        assertThat(approvals.get(1).getDraftVersion()).isEqualTo(3L);
    }

    @Test
    void listApprovalTasksShouldRestrictNonAdminToOwnApp() throws Exception {
        ExperimentMetadata appAMetadata = metadataFor("exp_app_a", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        appAMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        appAMetadata.setApprovalUpdatedAt(LocalDateTime.now());
        ExperimentMetadata appBMetadata = metadataFor("exp_app_b", "app-b", "owner-b",
                Experiment.ExperimentStatus.DRAFT);
        appBMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        appBMetadata.setApprovalUpdatedAt(LocalDateTime.now());
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_app_a", "exp_app_b"));
        when(configService.getExperimentConfig("exp_app_a")).thenReturn(appAMetadata);
        when(configService.getExperimentConfig("exp_app_b")).thenReturn(appBMetadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks(null, null, null);

        assertThat(approvalTasks)
                .extracting(ExperimentApprovalTaskResponse::getExperimentId)
                .containsExactly("exp_app_a");
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getApprovalOwner()).isEqualTo("owner-a");
        assertThat(approvalTask.getApprovable()).isTrue();
        assertThat(approvalTask.getApprovalDisabledReason()).isNull();
    }

    @Test
    void listApprovalTasksShouldExposeDisabledReasonForSelfReview() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_self_review_task", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_self_review_task"));
        when(configService.getExperimentConfig("exp_self_review_task")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks(null, null, null);

        assertThat(approvalTasks).hasSize(1);
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getApprovalRequestedBy()).isEqualTo("owner-a");
        assertThat(approvalTask.getApprovalOwner()).isEqualTo("owner-a");
        assertThat(approvalTask.getApprovable()).isFalse();
        assertThat(approvalTask.getApprovalDisabledReason()).isEqualTo("提交人不能审批自己的变更");
    }

    @Test
    void listApprovalTasksShouldExposeBlockingReportRisk() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_risk_task", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_risk_task"));
        when(configService.getExperimentConfig("exp_risk_task")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));
        ExperimentReportSnapshot snapshot = reportSnapshotFor("exp_risk_task", 2, true, true,
                List.of("护栏指标支付率下降"));
        when(analysisService.listReportSnapshots("exp_risk_task")).thenReturn(List.of(snapshot));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks("app-a", null, null);

        assertThat(approvalTasks).hasSize(1);
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getApprovalRiskLevel()).isEqualTo("BLOCKED");
        assertThat(approvalTask.getApprovalRiskFlags()).containsExactly("SRM", "GUARDRAIL_BREACHED");
        assertThat(approvalTask.getGuardrailStatus()).isEqualTo("BLOCKED");
        assertThat(approvalTask.getLatestReportSnapshotVersion()).isEqualTo(2);
        assertThat(approvalTask.getBreachedGuardrails()).containsExactly("护栏指标支付率下降");
        assertThat(approvalTask.getApprovable()).isFalse();
        assertThat(approvalTask.getApprovalDisabledReason()).contains("最新报告存在阻断风险");
        assertThat(approvalTask.getRiskOverrideRequired()).isTrue();
        assertThat(approvalTask.getRiskOverrideAllowed()).isTrue();
    }

    @Test
    void listApprovalTasksShouldExposeOverdueEscalationContext() throws Exception {
        Clock approvalTaskClock = Clock.fixed(Instant.parse("2026-07-17T04:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        ReflectionTestUtils.setField(experimentService, "approvalTaskClock", approvalTaskClock);
        LocalDateTime now = LocalDateTime.ofInstant(approvalTaskClock.instant(), approvalTaskClock.getZone());
        ExperimentMetadata metadata = metadataFor("exp_overdue_task", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        metadata.setApprovalUpdatedAt(now.minusHours(9));
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, true);
        applicationSpace.setApprovalSlaHours(8);
        applicationSpace.setApprovalEscalationOwners(List.of("ops-a", "ops-b"));
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_overdue_task"));
        when(configService.getExperimentConfig("exp_overdue_task")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks("app-a", null, null);

        assertThat(approvalTasks).hasSize(1);
        ExperimentApprovalTaskResponse approvalTask = approvalTasks.get(0);
        assertThat(approvalTask.getApprovalSubmittedAt()).isEqualTo(now.minusHours(9));
        assertThat(approvalTask.getApprovalElapsedHours()).isEqualTo(9L);
        assertThat(approvalTask.getApprovalSlaHours()).isEqualTo(8);
        assertThat(approvalTask.getApprovalSlaStatus()).isEqualTo("OVERDUE");
        assertThat(approvalTask.getApprovalEscalationOwners()).containsExactly("ops-a", "ops-b");
        assertThat(approvalTask.getApprovalEscalationReason()).contains("审批已超过 SLA 8 小时");
    }

    @Test
    void scanApprovalEscalationsShouldCreateOpenRecordForOverdueTask() throws Exception {
        Clock approvalTaskClock = Clock.fixed(Instant.parse("2026-07-17T04:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        ReflectionTestUtils.setField(experimentService, "approvalTaskClock", approvalTaskClock);
        LocalDateTime now = LocalDateTime.ofInstant(approvalTaskClock.instant(), approvalTaskClock.getZone());
        ExperimentMetadata metadata = metadataFor("exp_escalation_scan", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        metadata.setApprovalUpdatedAt(now.minusHours(10));
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, true);
        applicationSpace.setApprovalSlaHours(8);
        applicationSpace.setApprovalEscalationOwners(List.of("ops-a"));
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_escalation_scan"));
        when(configService.getExperimentConfig("exp_escalation_scan")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalEscalationRepository.findByTask("exp_escalation_scan",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L, now.minusHours(10)))
                .thenReturn(Optional.empty());
        when(experimentApprovalEscalationRepository.save(any(ExperimentApprovalEscalation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalEscalationResponse> escalations =
                experimentService.scanApprovalEscalations("app-a", null);

        assertThat(escalations).hasSize(1);
        ExperimentApprovalEscalationResponse escalation = escalations.get(0);
        assertThat(escalation.getExperimentId()).isEqualTo("exp_escalation_scan");
        assertThat(escalation.getEscalationStatus()).isEqualTo(ExperimentApprovalEscalationStatus.OPEN);
        assertThat(escalation.getApprovalElapsedHours()).isEqualTo(10L);
        assertThat(escalation.getApprovalSlaHours()).isEqualTo(8);
        assertThat(escalation.getEscalationOwners()).containsExactly("ops-a");
        assertThat(escalation.getNotificationChannel()).isEqualTo("APPROVAL_ESCALATION_OUTBOX");
        assertThat(escalation.getNotificationPayload()).containsEntry("messageType", "APPROVAL_ESCALATION");
    }

    @Test
    void acknowledgeApprovalEscalationShouldUpdateOpenRecord() {
        ExperimentApprovalEscalation openEscalation = escalationFor("esc_ack", "app-a",
                ExperimentApprovalEscalationStatus.OPEN);
        ExperimentApprovalEscalation acknowledgedEscalation = escalationFor("esc_ack", "app-a",
                ExperimentApprovalEscalationStatus.ACKNOWLEDGED);
        acknowledgedEscalation.setAcknowledgedBy("ops");
        acknowledgedEscalation.setAcknowledgedComment("已通知审批人");
        when(experimentApprovalEscalationRepository.findByEscalationId("esc_ack"))
                .thenReturn(Optional.of(openEscalation), Optional.of(acknowledgedEscalation));
        when(experimentApprovalEscalationRepository.acknowledge(eq("esc_ack"), eq("ops"),
                eq("已通知审批人"), any(LocalDateTime.class))).thenReturn(1);
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        ExperimentApprovalEscalationAcknowledgeRequest request =
                new ExperimentApprovalEscalationAcknowledgeRequest();
        request.setComment("已通知审批人");

        ExperimentApprovalEscalationResponse response =
                experimentService.acknowledgeApprovalEscalation("esc_ack", request);

        assertThat(response.getEscalationStatus()).isEqualTo(ExperimentApprovalEscalationStatus.ACKNOWLEDGED);
        assertThat(response.getAcknowledgedBy()).isEqualTo("ops");
        assertThat(response.getAcknowledgedComment()).isEqualTo("已通知审批人");
    }

    @Test
    void getApprovalEscalationStatusShouldAggregateDeliveryHealth() {
        when(experimentApprovalEscalationRepository.countByEscalationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationStatus.OPEN.name(), 2L),
                        statusCount(ExperimentApprovalEscalationStatus.ACKNOWLEDGED.name(), 1L),
                        statusCount(ExperimentApprovalEscalationStatus.RESOLVED.name(), 3L)));
        when(experimentApprovalEscalationRepository.countByNotificationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationNotificationStatus.PENDING.name(), 1L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.RETRY.name(), 1L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.DEAD.name(), 1L)));
        when(experimentApprovalEscalationRepository.countDeliveryByNotificationStatus(null, null))
                .thenReturn(List.of(
                        statusCount(ExperimentApprovalEscalationNotificationStatus.PENDING.name(), 2L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.SENT.name(), 3L),
                        statusCount(ExperimentApprovalEscalationNotificationStatus.DEAD.name(), 1L)));
        when(approvalEscalationNotificationDispatcher.isEnabled()).thenReturn(true);
        when(approvalEscalationNotificationDispatcher.targetCount()).thenReturn(2);
        when(approvalEscalationNotificationDispatcher.channelNames()).thenReturn(List.of("lark", "slack"));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        ExperimentApprovalEscalationStatusResponse response =
                experimentService.getApprovalEscalationStatus(null, null);

        assertThat(response.getTotalCount()).isEqualTo(6L);
        assertThat(response.getOpenCount()).isEqualTo(2L);
        assertThat(response.getAcknowledgedCount()).isEqualTo(1L);
        assertThat(response.getResolvedCount()).isEqualTo(3L);
        assertThat(response.getPendingCount()).isEqualTo(1L);
        assertThat(response.getRetryCount()).isEqualTo(1L);
        assertThat(response.getDeadCount()).isEqualTo(1L);
        assertThat(response.getUndeliveredCount()).isEqualTo(3L);
        assertThat(response.getDeliveryPendingCount()).isEqualTo(2L);
        assertThat(response.getDeliverySentCount()).isEqualTo(3L);
        assertThat(response.getDeliveryDeadCount()).isEqualTo(1L);
        assertThat(response.getDeliveryUndeliveredCount()).isEqualTo(3L);
        assertThat(response.getHealthy()).isFalse();
        assertThat(response.getStatus()).isEqualTo("DEAD");
        assertThat(response.getDispatcherEnabled()).isTrue();
        assertThat(response.getDispatcherTargetCount()).isEqualTo(2);
        assertThat(response.getDispatcherChannels()).containsExactly("lark", "slack");
    }

    @Test
    void retryApprovalEscalationNotificationShouldMoveDeadRecordToRetry() {
        ExperimentApprovalEscalation deadEscalation = escalationFor("esc_dead", "app-a",
                ExperimentApprovalEscalationStatus.OPEN);
        deadEscalation.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.DEAD);
        when(experimentApprovalEscalationRepository.findByEscalationId("esc_dead"))
                .thenReturn(Optional.of(deadEscalation));
        when(experimentApprovalEscalationRepository.retryDeadNotification(eq("esc_dead"), any(LocalDateTime.class)))
                .thenReturn(1);
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        ExperimentApprovalEscalationOperationResponse response =
                experimentService.retryApprovalEscalationNotification("esc_dead", "ops");

        assertThat(response.getEscalationId()).isEqualTo("esc_dead");
        assertThat(response.getAppId()).isEqualTo("app-a");
        assertThat(response.getOperation()).isEqualTo("RETRY_DEAD_NOTIFICATION");
        assertThat(response.getAffectedCount()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(experimentApprovalEscalationRepository).retryDeadNotification(
                eq("esc_dead"), any(LocalDateTime.class));
    }

    @Test
    void retryDeadApprovalEscalationNotificationsShouldScopeToPrincipalApp() {
        ApiKeyContextHolder.set(principal("app-a", "ops", ApiKeyScope.MANAGEMENT));
        when(experimentApprovalEscalationRepository.retryDeadNotifications(
                eq("app-a"), isNull(), any(LocalDateTime.class))).thenReturn(2);

        ExperimentApprovalEscalationOperationResponse response =
                experimentService.retryDeadApprovalEscalationNotifications(null, null, "ops");

        assertThat(response.getAppId()).isEqualTo("app-a");
        assertThat(response.getAffectedCount()).isEqualTo(2L);
        verify(experimentApprovalEscalationRepository).retryDeadNotifications(
                eq("app-a"), isNull(), any(LocalDateTime.class));
    }

    @Test
    void listApprovalTasksShouldSupportAllApprovalStatuses() throws Exception {
        ExperimentMetadata rejectedMetadata = metadataFor("exp_rejected", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        rejectedMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.REJECTED);
        rejectedMetadata.setApprovalUpdatedAt(LocalDateTime.now());
        ExperimentMetadata notRequiredMetadata = metadataFor("exp_open", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        notRequiredMetadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.NOT_REQUIRED);
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_rejected", "exp_open"));
        when(configService.getExperimentConfig("exp_rejected")).thenReturn(rejectedMetadata);
        when(configService.getExperimentConfig("exp_open")).thenReturn(notRequiredMetadata);
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));

        List<ExperimentApprovalTaskResponse> approvalTasks =
                experimentService.listApprovalTasks(null, null, "ALL");

        assertThat(approvalTasks)
                .extracting(ExperimentApprovalTaskResponse::getExperimentId)
                .containsExactly("exp_rejected");
    }

    @Test
    void startExperimentShouldRejectRunningExperimentInSameMutexLayer() throws Exception {
        ExperimentMetadata draftMetadata = metadataFor("exp_draft", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT, "checkout-layer");
        ExperimentMetadata runningMetadata = metadataFor("exp_running", "app-a", "owner-b",
                Experiment.ExperimentStatus.RUNNING, "checkout-layer");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft")).thenReturn(draftMetadata);
        when(configService.getLayerConfig("checkout-layer")).thenReturn(mutexLayer("checkout-layer"));
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_draft", "exp_running"));
        when(configService.getExperimentConfig("exp_running")).thenReturn(runningMetadata);

        assertThatThrownBy(() -> experimentService.startExperiment("exp_draft"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("互斥层 checkout-layer 已有运行中实验: exp_running");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startExperimentShouldAllowRunningExperimentInSameMutexLayerForDifferentApp() throws Exception {
        ExperimentMetadata draftMetadata = metadataFor("exp_draft", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT, "checkout-layer");
        ExperimentMetadata runningMetadata = metadataFor("exp_running", "app-b", "owner-b",
                Experiment.ExperimentStatus.RUNNING, "checkout-layer");
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft")).thenReturn(draftMetadata);
        when(configService.getLayerConfig("checkout-layer")).thenReturn(mutexLayer("checkout-layer"));
        when(configService.getAllExperimentIds()).thenReturn(List.of("exp_draft", "exp_running"));
        when(configService.getExperimentConfig("exp_running")).thenReturn(runningMetadata);

        experimentService.startExperiment("exp_draft");

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.eq("exp_draft"), captor.capture());
        assertThat(captor.getValue().getExperiment().getStatus()).isEqualTo(Experiment.ExperimentStatus.RUNNING);
    }

    @Test
    void startExperimentShouldRejectPendingApprovalWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_pending", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_pending")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));

        assertThatThrownBy(() -> experimentService.startExperiment("exp_pending"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实验启动前需要审批通过");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startExperimentShouldRejectOutsideApplicationReleaseWindow() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_release_window", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        ApplicationSpace applicationSpace = applicationSpace("app-a", "owner-a", 10, false);
        applicationSpace.setReleaseWindowEnabled(true);
        applicationSpace.setReleaseWindowTimezone("Asia/Shanghai");
        applicationSpace.setReleaseWindowDays(List.of(1, 2, 3, 4, 5));
        applicationSpace.setReleaseWindowStartTime("09:00");
        applicationSpace.setReleaseWindowEndTime("18:00");
        ReflectionTestUtils.setField(experimentService, "releaseWindowClock",
                Clock.fixed(Instant.parse("2026-07-18T02:00:00Z"), ZoneId.of("UTC")));
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_release_window")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));

        assertThatThrownBy(() -> experimentService.startExperiment("exp_release_window"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("启动实验不在应用发布窗口内");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateApprovalStatusShouldApprovePendingExperiment() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_pending", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_pending")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setOperator("approver-a");
        request.setComment(" ready ");

        experimentService.updateApprovalStatus("exp_pending", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.eq("exp_pending"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(captor.getValue().getApprovalOperator()).isEqualTo("owner-a");
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("ready");
    }

    @Test
    void updateApprovalStatusShouldKeepPendingUntilApprovalQuorumReached() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_quorum_pending", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_quorum_pending")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_quorum_pending",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(approvalVoteFor("exp_quorum_pending",
                        ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                        ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a")));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("first approval");

        experimentService.updateApprovalStatus("exp_quorum_pending", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_quorum_pending"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(captor.getValue().getApprovalOperator()).isEqualTo("owner-a");
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("审批进度 1/2");
    }

    @Test
    void updateApprovalStatusShouldUseApprovalPolicySnapshotForPendingTask() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_policy_snapshot", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        metadata.setApprovalOwnersSnapshot(List.of("reviewer-a", "reviewer-b"));
        metadata.setApprovalRequiredCountSnapshot(2);
        metadata.setApprovalPolicyVersion(7L);
        ApplicationSpace changedApplicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        changedApplicationSpace.setApprovalOwners(List.of("new-reviewer"));
        changedApplicationSpace.setApprovalRequiredCount(1);
        changedApplicationSpace.setApprovalPolicyVersion(8L);
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_policy_snapshot")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(changedApplicationSpace));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_policy_snapshot",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(approvalVoteFor("exp_policy_snapshot",
                        ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                        ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a")));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("first snapshot approval");

        experimentService.updateApprovalStatus("exp_policy_snapshot", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_policy_snapshot"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("审批进度 1/2");
        assertThat(captor.getValue().getApprovalPolicyVersion()).isEqualTo(7L);
    }

    @Test
    void updateApprovalStatusShouldApproveWhenApprovalQuorumReached() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_quorum_approved", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        ApiKeyContextHolder.set(principal("app-a", "reviewer-b", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_quorum_approved")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_quorum_approved",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(
                        approvalVoteFor("exp_quorum_approved", ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                                ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a"),
                        approvalVoteFor("exp_quorum_approved", ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                                ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-b")
                ));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment(" second approval ");

        experimentService.updateApprovalStatus("exp_quorum_approved", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_quorum_approved"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(captor.getValue().getApprovalOperator()).isEqualTo("reviewer-b");
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("second approval");
    }

    @Test
    void updateApprovalStatusShouldUpdateCurrentDraftApprovalRecord() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_draft_pending", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ExperimentConfigDraftApproval draftApproval = draftApprovalFor("exp_draft_pending", 3L, 7L,
                ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_pending")).thenReturn(metadata);
        when(configService.getCurrentExperimentConfigDraftApproval("exp_draft_pending"))
                .thenReturn(Optional.of(draftApproval));
        when(configService.updateExperimentConfigDraftApprovalStatus(eq("exp_draft_pending"), eq(3L),
                eq(ExperimentMetadata.ApprovalStatus.APPROVED), eq("reviewer-a"), eq("approve draft")))
                .thenReturn(Optional.of(draftApprovalFor("exp_draft_pending", 3L, 7L,
                        ExperimentMetadata.ApprovalStatus.APPROVED)));
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setOperator("approver-a");
        request.setComment(" approve draft ");

        experimentService.updateApprovalStatus("exp_draft_pending", request);

        verify(configService).updateExperimentConfigDraftApprovalStatus("exp_draft_pending", 3L,
                ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a", "approve draft");
    }

    @Test
    void updateApprovalStatusShouldKeepDraftPendingUntilApprovalQuorumReached() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_draft_quorum", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ExperimentConfigDraftApproval draftApproval = draftApprovalFor("exp_draft_quorum", 3L, 7L,
                ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_quorum")).thenReturn(metadata);
        when(configService.getCurrentExperimentConfigDraftApproval("exp_draft_quorum"))
                .thenReturn(Optional.of(draftApproval));
        when(configService.updateExperimentConfigDraftApprovalStatus(eq("exp_draft_quorum"), eq(3L),
                eq(ExperimentMetadata.ApprovalStatus.PENDING), eq("owner-a"), eq("审批进度 1/2")))
                .thenReturn(Optional.of(draftApproval));
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        applicationSpace.setApprovalRequiredCount(2);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_draft_quorum",
                ExperimentApprovalTaskType.CONFIG_DRAFT, 3L))
                .thenReturn(List.of(approvalVoteFor("exp_draft_quorum", ExperimentApprovalTaskType.CONFIG_DRAFT,
                        3L, ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a")));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("approve draft first");

        experimentService.updateApprovalStatus("exp_draft_quorum", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_draft_quorum"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.PENDING);
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("审批进度 1/2");
        verify(configService).updateExperimentConfigDraftApprovalStatus("exp_draft_quorum", 3L,
                ExperimentMetadata.ApprovalStatus.PENDING, "owner-a", "审批进度 1/2");
    }

    @Test
    void updateApprovalStatusShouldRejectNonApprovalOwner() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_pending_reviewer", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "member-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_pending_reviewer")).thenReturn(metadata);
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a", "reviewer-b"));
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("ready");

        assertThatThrownBy(() -> experimentService.updateApprovalStatus("exp_pending_reviewer", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅应用审批负责人可操作: reviewer-a,reviewer-b");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateApprovalStatusShouldRejectDraftRequesterSelfApproval() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_draft_self_review", "app-a", "owner-a",
                Experiment.ExperimentStatus.PAUSED);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        ExperimentConfigDraftApproval draftApproval = draftApprovalFor("exp_draft_self_review", 3L, 7L,
                ExperimentMetadata.ApprovalStatus.PENDING);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_draft_self_review")).thenReturn(metadata);
        when(configService.getCurrentExperimentConfigDraftApproval("exp_draft_self_review"))
                .thenReturn(Optional.of(draftApproval));
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("owner-a", "reviewer-b"));
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("self approve");

        assertThatThrownBy(() -> experimentService.updateApprovalStatus("exp_draft_self_review", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("提交人不能审批自己的变更");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateApprovalStatusShouldRejectApprovalWhenLatestReportHasBlockingRisk() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_blocked_risk", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a"));
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_blocked_risk")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(analysisService.listReportSnapshots("exp_blocked_risk"))
                .thenReturn(List.of(reportSnapshotFor("exp_blocked_risk", 3, true, false,
                        List.of("护栏指标支付率下降"))));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("approve with risk");

        assertThatThrownBy(() -> experimentService.updateApprovalStatus("exp_blocked_risk", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最新报告存在阻断风险");
        verify(experimentApprovalVoteRepository, never()).save(any(ExperimentApprovalVote.class));
        verify(configService, never()).saveExperimentConfig(eq("exp_blocked_risk"), any());
    }

    @Test
    void updateApprovalStatusShouldRejectRiskOverrideForNonAdmin() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_risk_override_member", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a"));
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_risk_override_member")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(analysisService.listReportSnapshots("exp_risk_override_member"))
                .thenReturn(List.of(reportSnapshotFor("exp_risk_override_member", 3, true, false,
                        List.of("护栏指标支付率下降"))));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("approve with risk");
        request.setRiskOverride(true);
        request.setRiskOverrideReason("业务窗口必须发布");

        assertThatThrownBy(() -> experimentService.updateApprovalStatus("exp_risk_override_member", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有 admin 可以豁免审批风险");
        verify(experimentApprovalVoteRepository, never()).save(any(ExperimentApprovalVote.class));
        verify(configService, never()).saveExperimentConfig(eq("exp_risk_override_member"), any());
    }

    @Test
    void updateApprovalStatusShouldAllowAdminRiskOverrideWithReason() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_risk_override_admin", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a"));
        ApiKeyContextHolder.set(principal("platform", "ops", ApiKeyScope.ADMIN));
        when(configService.getExperimentConfig("exp_risk_override_admin")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(analysisService.listReportSnapshots("exp_risk_override_admin"))
                .thenReturn(List.of(reportSnapshotFor("exp_risk_override_admin", 5, true, false,
                        List.of("护栏指标支付率下降"))));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_risk_override_admin",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(approvalVoteFor("exp_risk_override_admin",
                        ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                        ExperimentMetadata.ApprovalStatus.APPROVED, "ops")));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("override approved");
        request.setRiskOverride(true);
        request.setRiskOverrideReason("业务窗口必须发布");

        experimentService.updateApprovalStatus("exp_risk_override_admin", request);

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(eq("exp_risk_override_admin"), captor.capture());
        assertThat(captor.getValue().getApprovalStatus()).isEqualTo(ExperimentMetadata.ApprovalStatus.APPROVED);
        assertThat(captor.getValue().getApprovalOperator()).isEqualTo("ops");
        assertThat(captor.getValue().getApprovalComment()).isEqualTo("override approved");
    }

    @Test
    void updateApprovalStatusShouldResolveOpenEscalationsWhenApprovalFinishes() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_resolve_escalation", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.PENDING);
        metadata.setApprovalOperator("owner-a");
        ApplicationSpace applicationSpace = applicationSpace("app-a", "pm-a", 10, true);
        applicationSpace.setApprovalOwners(List.of("reviewer-a"));
        ApiKeyContextHolder.set(principal("app-a", "reviewer-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_resolve_escalation")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a")).thenReturn(Optional.of(applicationSpace));
        when(experimentApprovalVoteRepository.save(any(ExperimentApprovalVote.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(experimentApprovalVoteRepository.listByApprovalTask("exp_resolve_escalation",
                ExperimentApprovalTaskType.EXPERIMENT_START, 0L))
                .thenReturn(List.of(approvalVoteFor("exp_resolve_escalation",
                        ExperimentApprovalTaskType.EXPERIMENT_START, 0L,
                        ExperimentMetadata.ApprovalStatus.APPROVED, "reviewer-a")));
        ExperimentApprovalStatusUpdateRequest request = new ExperimentApprovalStatusUpdateRequest();
        request.setApprovalStatus("APPROVED");
        request.setComment("approved");

        experimentService.updateApprovalStatus("exp_resolve_escalation", request);

        verify(experimentApprovalEscalationRepository).resolveByTask(eq("exp_resolve_escalation"),
                eq(ExperimentApprovalTaskType.EXPERIMENT_START), eq(0L), eq("reviewer-a"),
                eq("审批状态已变更为 APPROVED"), any(LocalDateTime.class));
    }

    @Test
    void startExperimentShouldAllowApprovedExperimentWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_approved", "app-a", "owner-a",
                Experiment.ExperimentStatus.DRAFT);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_approved")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));

        experimentService.startExperiment("exp_approved");

        ArgumentCaptor<ExperimentMetadata> captor = ArgumentCaptor.forClass(ExperimentMetadata.class);
        verify(configService).saveExperimentConfig(org.mockito.ArgumentMatchers.eq("exp_approved"), captor.capture());
        assertThat(captor.getValue().getExperiment().getStatus()).isEqualTo(Experiment.ExperimentStatus.RUNNING);
    }

    @Test
    void updateExperimentShouldRejectRunningChangeWhenApplicationRequiresApproval() throws Exception {
        ExperimentMetadata metadata = metadataFor("exp_running", "app-a", "owner-a",
                Experiment.ExperimentStatus.RUNNING);
        metadata.setApprovalStatus(ExperimentMetadata.ApprovalStatus.APPROVED);
        ApiKeyContextHolder.set(principal("app-a", "owner-a", ApiKeyScope.MANAGEMENT));
        when(configService.getExperimentConfig("exp_running")).thenReturn(metadata);
        when(applicationSpaceRepository.findByAppId("app-a"))
                .thenReturn(Optional.of(applicationSpace("app-a", "owner-a", 10, true)));

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_running", buildRequest("运行中变更")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("启用审批的应用不允许直接更新运行中实验");
        verify(configService, never()).saveExperimentConfig(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private ExperimentCreateRequest buildRequest(String name) {
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName(name);
        request.setDescription("desc");
        request.setStartTime(LocalDateTime.now().plusHours(1));
        request.setEndTime(LocalDateTime.now().plusDays(7));

        ExperimentCreateRequest.GroupConfig groupA = new ExperimentCreateRequest.GroupConfig();
        groupA.setId("A");
        groupA.setName("基准组");
        groupA.setTrafficRatio(0.5);

        ExperimentCreateRequest.GroupConfig groupB = new ExperimentCreateRequest.GroupConfig();
        groupB.setId("B");
        groupB.setName("变体组");
        groupB.setTrafficRatio(0.5);
        request.setGroups(List.of(groupA, groupB));

        ExperimentCreateRequest.GroupAllocationRequest allocationA =
                new ExperimentCreateRequest.GroupAllocationRequest();
        allocationA.setGroup("A");
        allocationA.setRatio(0.5);

        ExperimentCreateRequest.GroupAllocationRequest allocationB =
                new ExperimentCreateRequest.GroupAllocationRequest();
        allocationB.setGroup("B");
        allocationB.setRatio(0.5);

        ExperimentCreateRequest.TrafficConfigRequest traffic = new ExperimentCreateRequest.TrafficConfigRequest();
        traffic.setTotalTraffic(1.0);
        traffic.setStrategy("HASH");
        traffic.setAllocation(List.of(allocationA, allocationB));
        request.setTraffic(traffic);
        request.setEventDefinitions(List.of(
                eventDefinition("PRODUCT_VIEW", "商品查看", true),
                eventDefinition("PAY_SUCCESS", "支付成功", false)
        ));
        request.setMetricDefinitions(List.of(
                metricDefinition("PAY_RATE", "支付率", "PAY_SUCCESS", "PRODUCT_VIEW", true, false)
        ));
        return request;
    }

    private ExperimentConfigDraftSaveRequest buildDraftRequest(String name) {
        ExperimentCreateRequest baseRequest = buildRequest(name);
        ExperimentConfigDraftSaveRequest request = new ExperimentConfigDraftSaveRequest();
        request.setName(baseRequest.getName());
        request.setDescription(baseRequest.getDescription());
        request.setAppId(baseRequest.getAppId());
        request.setOwner(baseRequest.getOwner());
        request.setLayerId(baseRequest.getLayerId());
        request.setStartTime(baseRequest.getStartTime());
        request.setEndTime(baseRequest.getEndTime());
        request.setGroups(baseRequest.getGroups());
        request.setTraffic(baseRequest.getTraffic());
        request.setWhitelist(baseRequest.getWhitelist());
        request.setBlacklist(baseRequest.getBlacklist());
        request.setEventDefinitions(baseRequest.getEventDefinitions());
        request.setMetricDefinitions(baseRequest.getMetricDefinitions());
        request.setGroupConfigSchema(baseRequest.getGroupConfigSchema());
        return request;
    }

    private ExperimentMetadata metadataFor(String experimentId, String appId, String owner,
                                           Experiment.ExperimentStatus status) {
        return metadataFor(experimentId, appId, owner, status, null);
    }

    private ExperimentMetadata metadataFor(String experimentId, String appId, String owner,
                                           Experiment.ExperimentStatus status, String layerId) {
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName("实验-" + experimentId);
        experiment.setStatus(status);
        experiment.setAppId(appId);
        experiment.setOwner(owner);
        experiment.setCreator(owner);

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setLayerId(layerId);
        metadata.setAppId(appId);
        metadata.setOwner(owner);
        metadata.setExperiment(experiment);
        return metadata;
    }

    private ExperimentLayer mutexLayer(String layerId) {
        ExperimentLayer layer = new ExperimentLayer();
        layer.setLayerId(layerId);
        layer.setStrategy(ExperimentLayer.LayerStrategy.MUTEX);
        return layer;
    }

    private ApplicationSpace applicationSpace(String appId, String defaultOwner, Integer experimentQuota) {
        return applicationSpace(appId, defaultOwner, experimentQuota, false);
    }

    private ApplicationSpace applicationSpace(String appId, String defaultOwner, Integer experimentQuota,
                                              boolean approvalRequired) {
        ApplicationSpace applicationSpace = new ApplicationSpace();
        applicationSpace.setAppId(appId);
        applicationSpace.setDefaultOwner(defaultOwner);
        applicationSpace.setExperimentQuota(experimentQuota);
        applicationSpace.setApprovalRequired(approvalRequired);
        return applicationSpace;
    }

    private ApiKeyPrincipal principal(String appId, String owner, ApiKeyScope scope) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(appId);
        principal.setOwner(owner);
        principal.setScopes(Set.of(scope));
        return principal;
    }

    private ExperimentConfigVersion versionFor(ExperimentMetadata metadata, String publishedBy, String publishComment,
                                               Long sourceConfigVersion, String sourceType) {
        ExperimentConfigVersion version = new ExperimentConfigVersion();
        version.setExperimentId(metadata.getExperiment().getId());
        version.setConfigVersion(metadata.getConfigVersion());
        version.setMetadata(metadata);
        version.setPublishedBy(publishedBy);
        version.setPublishComment(publishComment);
        version.setSourceConfigVersion(sourceConfigVersion);
        version.setSourceType(sourceType);
        version.setPublishedAt(LocalDateTime.now());
        return version;
    }

    private ExperimentConfigDraft draftFor(ExperimentMetadata metadata, Long draftVersion, Long baseConfigVersion,
                                           String updatedBy, String draftComment) {
        ExperimentConfigDraft draft = new ExperimentConfigDraft();
        draft.setExperimentId(metadata.getExperiment().getId());
        draft.setDraftVersion(draftVersion);
        draft.setBaseConfigVersion(baseConfigVersion);
        draft.setMetadata(metadata);
        draft.setUpdatedBy(updatedBy);
        draft.setDraftComment(draftComment);
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        return draft;
    }

    private ExperimentConfigDraftApproval draftApprovalFor(String experimentId, Long draftVersion,
                                                           Long baseConfigVersion,
                                                           ExperimentMetadata.ApprovalStatus approvalStatus) {
        ExperimentConfigDraftApproval approval = new ExperimentConfigDraftApproval();
        approval.setExperimentId(experimentId);
        approval.setDraftVersion(draftVersion);
        approval.setBaseConfigVersion(baseConfigVersion);
        approval.setApprovalStatus(approvalStatus);
        approval.setRequestedBy("owner-a");
        approval.setDraftComment("draft ready");
        approval.setApprovalOperator("owner-a");
        approval.setApprovalComment("approval comment");
        approval.setApprovalUpdatedAt(LocalDateTime.now());
        return approval;
    }

    private ExperimentApprovalVote approvalVoteFor(String experimentId, ExperimentApprovalTaskType approvalType,
                                                   Long draftVersion,
                                                   ExperimentMetadata.ApprovalStatus approvalStatus,
                                                   String approvalOperator) {
        ExperimentApprovalVote vote = new ExperimentApprovalVote();
        vote.setExperimentId(experimentId);
        vote.setApprovalType(approvalType);
        vote.setDraftVersion(draftVersion);
        vote.setApprovalStatus(approvalStatus);
        vote.setApprovalOperator(approvalOperator);
        vote.setApprovalComment("vote comment");
        return vote;
    }

    private ExperimentApprovalEscalation escalationFor(String escalationId, String appId,
                                                       ExperimentApprovalEscalationStatus escalationStatus) {
        ExperimentApprovalEscalation escalation = new ExperimentApprovalEscalation();
        escalation.setEscalationId(escalationId);
        escalation.setExperimentId("exp_ack");
        escalation.setApprovalType(ExperimentApprovalTaskType.EXPERIMENT_START);
        escalation.setDraftVersion(0L);
        escalation.setAppId(appId);
        escalation.setOwner("owner-a");
        escalation.setExperimentName("审批告警实验");
        escalation.setApprovalSubmittedAt(LocalDateTime.now().minusHours(9));
        escalation.setApprovalElapsedHours(9L);
        escalation.setApprovalSlaHours(8);
        escalation.setApprovalSlaStatus("OVERDUE");
        escalation.setEscalationOwners(List.of("ops"));
        escalation.setEscalationReason("审批已超过 SLA 8 小时");
        escalation.setNotificationChannel("APPROVAL_ESCALATION_OUTBOX");
        escalation.setNotificationPayload(Map.of("messageType", "APPROVAL_ESCALATION"));
        escalation.setNotificationStatus(ExperimentApprovalEscalationNotificationStatus.PENDING);
        escalation.setNotificationAttemptCount(0);
        escalation.setEscalationStatus(escalationStatus);
        return escalation;
    }

    private ExperimentApprovalEscalationStatusCountEntity statusCount(String status, Long count) {
        ExperimentApprovalEscalationStatusCountEntity countEntity =
                new ExperimentApprovalEscalationStatusCountEntity();
        countEntity.setStatus(status);
        countEntity.setEscalationCount(count);
        return countEntity;
    }

    private ExperimentReportSnapshot reportSnapshotFor(String experimentId, Integer snapshotVersion,
                                                       Boolean analysisReady, Boolean hasSrm,
                                                       List<String> breachedGuardrails) {
        ExperimentReportSnapshot snapshot = new ExperimentReportSnapshot();
        snapshot.setExperimentId(experimentId);
        snapshot.setSnapshotVersion(snapshotVersion);
        snapshot.setAnalysisReady(analysisReady);
        snapshot.setHasSrm(hasSrm);
        snapshot.setBreachedGuardrails(breachedGuardrails);
        snapshot.setGeneratedAt(LocalDateTime.now());
        return snapshot;
    }

    private ExperimentConclusionStatusUpdateRequest conclusionStatusRequest(
            ExperimentMetadata.ConclusionStatus status,
            Long expectedConfigVersion,
            Integer reportSnapshotVersion,
            String comment) {
        ExperimentConclusionStatusUpdateRequest request = new ExperimentConclusionStatusUpdateRequest();
        request.setConclusionStatus(status.name());
        request.setExpectedConfigVersion(expectedConfigVersion);
        request.setReportSnapshotVersion(reportSnapshotVersion);
        request.setComment(comment);
        return request;
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setPrimary(primary);
        eventDefinition.setCategory("BUSINESS");
        return eventDefinition;
    }

    private MetricDefinition metricDefinition(String key, String name, String numeratorEventType,
                                              String denominatorEventType, boolean primaryMetric,
                                              boolean guardrailMetric) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(name);
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(numeratorEventType);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(denominatorEventType);
        metricDefinition.setPrimaryMetric(primaryMetric);
        metricDefinition.setGuardrailMetric(guardrailMetric);
        return metricDefinition;
    }

    private GroupConfigFieldDefinition schemaField(String key, String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required, Object defaultValue) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(valueType);
        fieldDefinition.setRequired(required);
        fieldDefinition.setDefaultValue(defaultValue);
        return fieldDefinition;
    }
}
