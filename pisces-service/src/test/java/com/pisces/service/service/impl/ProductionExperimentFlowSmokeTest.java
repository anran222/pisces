package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentAssignment;
import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.common.model.ExperimentLayer;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.AuditLogResponse;
import com.pisces.common.response.EventPipelineOperationResponse;
import com.pisces.common.response.EventPipelineStatusResponse;
import com.pisces.common.response.EventReplayJobResponse;
import com.pisces.common.response.TrafficAssignmentResponse;
import com.pisces.service.audit.AuditLogConstants;
import com.pisces.service.audit.AuditLogRecord;
import com.pisces.service.entity.EventInboxStatusCountEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.event.EventMaterializationRecord;
import com.pisces.service.event.EventReplayJobRecord;
import com.pisces.service.repository.AuditLogRepository;
import com.pisces.service.repository.EventInboxRepository;
import com.pisces.service.repository.EventMaterializationRepository;
import com.pisces.service.repository.EventReplayJobRepository;
import com.pisces.service.repository.ExperimentAssignmentRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyScope;
import com.pisces.service.service.ConfigService;
import com.pisces.service.util.JsonUtil;
import com.pisces.service.validation.ExperimentPreflightValidator;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 生产实验主链路冒烟测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 10:06
 */
class ProductionExperimentFlowSmokeTest {

    private static final String BASELINE_GROUP = "A";
    private static final String VARIANT_GROUP = "B";
    private static final String PRODUCT_VIEW_EVENT = "PRODUCT_VIEW";
    private static final String PAY_SUCCESS_EVENT = "PAY_SUCCESS";
    private static final String PAY_RATE_METRIC = "PAY_RATE";
    private static final String CLIENT_EVENT_ID = "clientEventId";
    private static final double BASELINE_RATIO = 0.5D;
    private static final int MAX_VISITOR_SEARCH_COUNT = 10_000;

    @Test
    void realExperimentFlowShouldCreateStartAssignCollectAndAnalyze() throws Exception {
        TestHarness harness = new TestHarness();
        Experiment experiment = harness.experimentService.createExperiment(buildCreateRequest());
        String experimentId = experiment.getId();
        harness.experimentService.startExperiment(experimentId);

        String baselineVisitorId = findVisitorIdForGroup(experimentId, BASELINE_GROUP);
        String variantVisitorId = findVisitorIdForGroup(experimentId, VARIANT_GROUP);

        String baselineAssignedGroup = harness.trafficService.assignGroup(experimentId, baselineVisitorId,
                Map.of("city", "shanghai"));
        String variantAssignedGroup = harness.trafficService.assignGroup(experimentId, variantVisitorId,
                Map.of("city", "beijing"));
        TrafficAssignmentResponse tracedAssignment =
                harness.trafficService.assignGroupWithTrace(experimentId, baselineVisitorId, Map.of("city", "shanghai"));

        harness.dataService.reportExposure(experimentId, baselineVisitorId, Map.of("scene", "detail"));
        harness.dataService.reportExposure(experimentId, variantVisitorId, Map.of("scene", "detail"));
        harness.dataService.reportEvent(experimentId, baselineVisitorId, PRODUCT_VIEW_EVENT, PRODUCT_VIEW_EVENT,
                Map.of(CLIENT_EVENT_ID, baselineVisitorId + "-" + PRODUCT_VIEW_EVENT));
        harness.dataService.reportEvent(experimentId, variantVisitorId, PRODUCT_VIEW_EVENT, PRODUCT_VIEW_EVENT,
                Map.of(CLIENT_EVENT_ID, variantVisitorId + "-" + PRODUCT_VIEW_EVENT));
        harness.dataService.reportEvent(experimentId, variantVisitorId, PAY_SUCCESS_EVENT, PAY_SUCCESS_EVENT,
                Map.of(CLIENT_EVENT_ID, variantVisitorId + "-" + PAY_SUCCESS_EVENT));
        harness.dataService.reportEvent(experimentId, variantVisitorId, PAY_SUCCESS_EVENT, PAY_SUCCESS_EVENT,
                Map.of(CLIENT_EVENT_ID, variantVisitorId + "-" + PAY_SUCCESS_EVENT));
        int processedCount = harness.eventInboxConsumer.processDueRecords();

        Statistics statistics = harness.analysisService.getStatistics(experimentId);
        EventPipelineStatusResponse pipelineStatus = harness.analysisService.getEventPipelineStatus(experimentId);
        EventPipelineOperationResponse replayResponse =
                harness.analysisService.replayEventPipeline(experimentId, "tester");
        EventReplayJobResponse replayJobResponse =
                harness.analysisService.getEventReplayJob(experimentId, replayResponse.getReplayJobId());
        ExperimentConclusionStatusUpdateRequest conclusionRequest = new ExperimentConclusionStatusUpdateRequest();
        conclusionRequest.setConclusionStatus(ExperimentMetadata.ConclusionStatus.RUNNING.name());
        conclusionRequest.setOperator("tester");
        harness.experimentService.updateConclusionStatus(experimentId, conclusionRequest);
        List<AuditLogResponse> auditLogs = harness.experimentService.listExperimentAuditLogs(experimentId);

        assertThat(harness.configService.getExperimentConfig(experimentId).getExperiment().getStatus())
                .isEqualTo(Experiment.ExperimentStatus.RUNNING);
        assertThat(harness.configService.getExperimentConfig(experimentId).getConclusionStatus())
                .isEqualTo(ExperimentMetadata.ConclusionStatus.RUNNING);
        assertThat(baselineAssignedGroup).isEqualTo(BASELINE_GROUP);
        assertThat(variantAssignedGroup).isEqualTo(VARIANT_GROUP);
        assertThat(tracedAssignment.getGroupId()).isEqualTo(BASELINE_GROUP);
        assertThat(tracedAssignment.getSource()).isEqualTo("NEW_ASSIGNMENT");
        assertThat(tracedAssignment.getReason()).isEqualTo("ALLOCATED");
        assertThat(tracedAssignment.getStrategy()).isEqualTo("HASH");
        assertThat(tracedAssignment.getConfigVersion()).isEqualTo(1L);
        assertThat(processedCount).isEqualTo(5);
        assertThat(statistics).isNotNull();
        assertThat(statistics.getSummary().getTotalAssignments()).isEqualTo(2L);
        assertThat(statistics.getSummary().getTotalExposures()).isEqualTo(2L);
        assertThat(statistics.getSummary().getTotalVisitors()).isEqualTo(2L);
        assertThat(statistics.getSummary().getTotalEvents()).isEqualTo(3L);
        assertThat(statistics.getSummary().getPrimaryMetricKey()).isEqualTo(PAY_RATE_METRIC);
        assertThat(statistics.getSummary().getBestPerformingGroup()).isEqualTo(VARIANT_GROUP);
        assertThat(statistics.getSummary().getBestPrimaryMetricValue()).isEqualTo(1.0D);
        assertThat(statistics.getDataQualityCheck().getAnalysisReady()).isTrue();
        assertThat(pipelineStatus.getTotalCount()).isEqualTo(5L);
        assertThat(pipelineStatus.getDoneCount()).isEqualTo(5L);
        assertThat(pipelineStatus.getPendingCount()).isZero();
        assertThat(pipelineStatus.getRetryCount()).isZero();
        assertThat(pipelineStatus.getDeadCount()).isZero();
        assertThat(pipelineStatus.getStatus()).isEqualTo(EventInboxConstants.STATUS_DONE);
        assertThat(pipelineStatus.getHealthy()).isTrue();
        assertThat(replayResponse.getOperation()).isEqualTo("REPLAY_DERIVED");
        assertThat(replayResponse.getStatus()).isEqualTo("RUNNING");
        assertThat(replayResponse.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_RUNNING);
        assertThat(replayJobResponse.getJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_SUCCEEDED);
        assertThat(replayJobResponse.getAffectedCount()).isEqualTo(5L);
        assertThat(replayJobResponse.getEventCount()).isEqualTo(3L);
        assertThat(replayJobResponse.getExposureCount()).isEqualTo(2L);
        assertThat(replayJobResponse.getGroupCount()).isEqualTo(2L);
        assertThat(replayJobResponse.getPlannedAffectedCount()).isEqualTo(5L);
        assertThat(replayJobResponse.getPlannedEventCount()).isEqualTo(3L);
        assertThat(replayJobResponse.getPlannedExposureCount()).isEqualTo(2L);
        assertThat(replayJobResponse.getPlannedGroupCount()).isEqualTo(2L);
        assertThat(replayJobResponse.getProgressPercent()).isEqualTo(100);
        assertThat(auditLogs)
                .extracting(AuditLogResponse::getAction)
                .contains(AuditLogConstants.ACTION_EXPERIMENT_CREATE,
                        AuditLogConstants.ACTION_EXPERIMENT_START,
                        AuditLogConstants.ACTION_CONCLUSION_STATUS_UPDATE);
        AuditLogResponse startAuditLog = findAuditLog(auditLogs, AuditLogConstants.ACTION_EXPERIMENT_START);
        assertThat(startAuditLog.getBeforeStatus()).isEqualTo(Experiment.ExperimentStatus.DRAFT.name());
        assertThat(startAuditLog.getAfterStatus()).isEqualTo(Experiment.ExperimentStatus.RUNNING.name());
        AuditLogResponse conclusionAuditLog =
                findAuditLog(auditLogs, AuditLogConstants.ACTION_CONCLUSION_STATUS_UPDATE);
        assertThat(conclusionAuditLog.getOperator()).isEqualTo("tester");
        assertThat(conclusionAuditLog.getBeforeStatus())
                .isEqualTo(ExperimentMetadata.ConclusionStatus.RUNNING.name());
        assertThat(conclusionAuditLog.getAfterStatus()).isEqualTo(ExperimentMetadata.ConclusionStatus.RUNNING.name());

        Statistics.GroupStatistics baselineStatistics = statistics.getGroupStatistics().get(BASELINE_GROUP);
        Statistics.GroupStatistics variantStatistics = statistics.getGroupStatistics().get(VARIANT_GROUP);
        assertThat(baselineStatistics.getAssignmentCount()).isEqualTo(1L);
        assertThat(baselineStatistics.getExposureCount()).isEqualTo(1L);
        assertThat(variantStatistics.getAssignmentCount()).isEqualTo(1L);
        assertThat(variantStatistics.getExposureCount()).isEqualTo(1L);
        assertThat(baselineStatistics.getMetricValues()).containsEntry(PAY_RATE_METRIC, 0.0D);
        assertThat(variantStatistics.getMetricValues()).containsEntry(PAY_RATE_METRIC, 1.0D);
        assertThat(baselineStatistics.getEventCounts()).containsEntry(PRODUCT_VIEW_EVENT, 1L);
        assertThat(variantStatistics.getEventCounts())
                .containsEntry(PRODUCT_VIEW_EVENT, 1L)
                .containsEntry(PAY_SUCCESS_EVENT, 1L);
    }

    @Test
    void retryDeadEventsShouldMoveDeadInboxRecordsBackToRetry() {
        TestHarness harness = new TestHarness();
        Experiment experiment = harness.experimentService.createExperiment(buildCreateRequest());
        String experimentId = experiment.getId();
        EventInboxRecord deadRecord = new EventInboxRecord();
        deadRecord.setInboxId("inbox_dead_1");
        deadRecord.setExperimentId(experimentId);
        deadRecord.setVisitorId("visitor-dead");
        deadRecord.setGroupId(BASELINE_GROUP);
        deadRecord.setEventKind(EventInboxConstants.KIND_EVENT);
        deadRecord.setEventType(PAY_SUCCESS_EVENT);
        deadRecord.setIdempotencyKey("EVENT:" + experimentId + ":dead-1");
        deadRecord.setStatus(EventInboxConstants.STATUS_DEAD);
        deadRecord.setRetryCount(5);
        deadRecord.setNextRetryAt(LocalDateTime.now().minusMinutes(10));
        deadRecord.setAcceptedAt(LocalDateTime.now().minusMinutes(20));
        deadRecord.setProcessedAt(LocalDateTime.now().minusMinutes(5));
        harness.eventInboxRepository.saveIfAbsent(deadRecord);

        EventPipelineOperationResponse operationResponse =
                harness.analysisService.retryDeadEvents(experimentId, "tester");
        EventPipelineStatusResponse pipelineStatus = harness.analysisService.getEventPipelineStatus(experimentId);

        assertThat(operationResponse.getOperation()).isEqualTo("RETRY_DEAD");
        assertThat(operationResponse.getAffectedCount()).isEqualTo(1L);
        assertThat(pipelineStatus.getRetryCount()).isEqualTo(1L);
        assertThat(pipelineStatus.getDeadCount()).isZero();
        assertThat(pipelineStatus.getStatus()).isEqualTo(EventInboxConstants.STATUS_RETRY);
    }

    @Test
    void assignGroupWithTraceShouldDegradeWhenRedisCacheUnavailable() {
        TestHarness harness = new TestHarness();
        Experiment experiment = harness.experimentService.createExperiment(buildCreateRequest());
        String experimentId = experiment.getId();
        harness.experimentService.startExperiment(experimentId);
        ReflectionTestUtils.setField(harness.identityService, "redisTemplate", mock(RedisTemplate.class));
        ReflectionTestUtils.setField(harness.trafficService, "redisTemplate", mock(RedisTemplate.class));

        String visitorId = findVisitorIdForGroup(experimentId, BASELINE_GROUP);
        TrafficAssignmentResponse response =
                harness.trafficService.assignGroupWithTrace(experimentId, visitorId, Map.of("city", "shanghai"));

        assertThat(response.getGroupId()).isEqualTo(BASELINE_GROUP);
        assertThat(response.getSource()).isEqualTo("NEW_ASSIGNMENT");
        assertThat(response.getReason()).isEqualTo("ALLOCATED");
        assertThat(harness.assignmentRepository.countByExperimentIdAndGroupId(experimentId, BASELINE_GROUP))
                .isEqualTo(1L);
    }

    @Test
    void appScopedContextShouldRejectCrossAppRuntimeAndAnalysisAccess() {
        TestHarness harness = new TestHarness();
        try {
            ApiKeyContextHolder.set(principal("app-a", "owner-a"));
            Experiment experiment = harness.experimentService.createExperiment(buildCreateRequest());
            String experimentId = experiment.getId();
            harness.experimentService.startExperiment(experimentId);

            ApiKeyContextHolder.set(principal("app-b", "owner-b"));

            assertThat(harness.experimentService.listExperiments()).isEmpty();
            assertThatThrownBy(() -> harness.trafficService.assignGroupWithTrace(experimentId,
                    "visitor-cross-app", Map.of()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                            .isEqualTo(ResponseCode.FORBIDDEN));
            assertThatThrownBy(() -> harness.analysisService.getEventPipelineStatus(experimentId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                            .isEqualTo(ResponseCode.FORBIDDEN));
        } finally {
            ApiKeyContextHolder.clear();
        }
    }

    private ExperimentCreateRequest buildCreateRequest() {
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName("生产级真实接入冒烟实验");
        request.setDescription("覆盖创建、启动、分流、曝光、事件和统计主链路");
        request.setStartTime(LocalDateTime.now().minusMinutes(1));
        request.setEndTime(LocalDateTime.now().plusDays(7));
        request.setGroups(List.of(group(BASELINE_GROUP, "基准组"), group(VARIANT_GROUP, "实验组")));
        request.setTraffic(trafficConfig());
        request.setEventDefinitions(List.of(
                eventDefinition(PRODUCT_VIEW_EVENT, "商品查看", true),
                eventDefinition(PAY_SUCCESS_EVENT, "支付成功", false)
        ));
        request.setMetricDefinitions(List.of(payRateMetric()));
        return request;
    }

    private AuditLogResponse findAuditLog(List<AuditLogResponse> auditLogs, String action) {
        return auditLogs.stream()
                .filter(auditLog -> action.equals(auditLog.getAction()))
                .findFirst()
                .orElseThrow();
    }

    private ExperimentCreateRequest.GroupConfig group(String groupId, String name) {
        ExperimentCreateRequest.GroupConfig groupConfig = new ExperimentCreateRequest.GroupConfig();
        groupConfig.setId(groupId);
        groupConfig.setName(name);
        groupConfig.setTrafficRatio(BASELINE_RATIO);
        groupConfig.setConfig(Map.of("title", name));
        return groupConfig;
    }

    private ExperimentCreateRequest.TrafficConfigRequest trafficConfig() {
        ExperimentCreateRequest.TrafficConfigRequest trafficConfig = new ExperimentCreateRequest.TrafficConfigRequest();
        trafficConfig.setTotalTraffic(1.0D);
        trafficConfig.setStrategy("HASH");
        trafficConfig.setAllocation(List.of(allocation(BASELINE_GROUP), allocation(VARIANT_GROUP)));
        return trafficConfig;
    }

    private ExperimentCreateRequest.GroupAllocationRequest allocation(String groupId) {
        ExperimentCreateRequest.GroupAllocationRequest allocation = new ExperimentCreateRequest.GroupAllocationRequest();
        allocation.setGroup(groupId);
        allocation.setRatio(BASELINE_RATIO);
        return allocation;
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setCategory("BUSINESS");
        eventDefinition.setPrimary(primary);
        return eventDefinition;
    }

    private MetricDefinition payRateMetric() {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(PAY_RATE_METRIC);
        metricDefinition.setName("支付率");
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(PAY_SUCCESS_EVENT);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(PRODUCT_VIEW_EVENT);
        metricDefinition.setPrimaryMetric(true);
        metricDefinition.setGuardrailMetric(false);
        return metricDefinition;
    }

    private String findVisitorIdForGroup(String experimentId, String groupId) {
        for (int index = 0; index < MAX_VISITOR_SEARCH_COUNT; index++) {
            String visitorId = "visitor-" + groupId.toLowerCase() + "-" + index;
            double hash = generateHashValue(visitorId + experimentId);
            if (BASELINE_GROUP.equals(groupId) && hash < BASELINE_RATIO) {
                return visitorId;
            }
            if (VARIANT_GROUP.equals(groupId) && hash >= BASELINE_RATIO) {
                return visitorId;
            }
        }
        throw new IllegalStateException("未找到可稳定命中实验组的访客: " + groupId);
    }

    private double generateHashValue(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            long hash = 0L;
            for (int index = 0; index < 4; index++) {
                hash = (hash << 8) | (hashBytes[index] & 0xFF);
            }
            return (hash & 0xFFFFFFFFL) / 4_294_967_296.0D;
        } catch (Exception exception) {
            throw new IllegalStateException("生成测试哈希失败", exception);
        }
    }

    private ApiKeyPrincipal principal(String appId, String owner) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(appId);
        principal.setOwner(owner);
        principal.setScopes(Set.of(ApiKeyScope.MANAGEMENT, ApiKeyScope.RUNTIME, ApiKeyScope.ANALYSIS));
        return principal;
    }

    private static final class TestHarness {

        private final InMemoryConfigService configService = new InMemoryConfigService();
        private final InMemoryAssignmentRepository assignmentRepository = new InMemoryAssignmentRepository();
        private final InMemoryExposureRepository exposureRepository = new InMemoryExposureRepository();
        private final InMemoryEventRepository eventRepository = new InMemoryEventRepository();
        private final InMemoryEventInboxRepository eventInboxRepository = new InMemoryEventInboxRepository();
        private final InMemoryEventMaterializationRepository eventMaterializationRepository =
                new InMemoryEventMaterializationRepository();
        private final InMemoryEventReplayJobRepository eventReplayJobRepository = new InMemoryEventReplayJobRepository();
        private final InMemoryAuditLogRepository auditLogRepository = new InMemoryAuditLogRepository();
        private final ExperimentServiceImpl experimentService = new ExperimentServiceImpl();
        private final IdentityServiceImpl identityService = new IdentityServiceImpl();
        private final TrafficServiceImpl trafficService = new TrafficServiceImpl();
        private final DataServiceImpl dataService = new DataServiceImpl();
        private final AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        private final AuditLogServiceImpl auditLogService = new AuditLogServiceImpl(auditLogRepository);
        private final EventInboxMaterializer eventInboxMaterializer;
        private final EventInboxConsumer eventInboxConsumer;

        private TestHarness() {
            RedisTemplate<String, Object> redisTemplate = buildRedisTemplate();
            TrafficRuleEvaluator trafficRuleEvaluator = new TrafficRuleEvaluator();
            GroupConfigSchemaValidator schemaValidator =
                    new GroupConfigSchemaValidator(new JsonUtil(new ObjectMapper()));
            eventInboxMaterializer = new EventInboxMaterializer(
                    eventRepository, exposureRepository, eventMaterializationRepository, configService,
                    redisTemplate);
            eventInboxConsumer = new EventInboxConsumer(eventInboxRepository, eventInboxMaterializer);

            ReflectionTestUtils.setField(experimentService, "configService", configService);
            ReflectionTestUtils.setField(experimentService, "trafficRuleEvaluator", trafficRuleEvaluator);
            ReflectionTestUtils.setField(experimentService, "groupConfigSchemaValidator", schemaValidator);
            ReflectionTestUtils.setField(experimentService, "experimentPreflightValidator",
                    new ExperimentPreflightValidator(schemaValidator, trafficRuleEvaluator));
            ReflectionTestUtils.setField(experimentService, "auditLogService", auditLogService);

            ReflectionTestUtils.setField(trafficService, "configService", configService);
            ReflectionTestUtils.setField(trafficService, "identityService", identityService);
            ReflectionTestUtils.setField(trafficService, "trafficRuleEvaluator", trafficRuleEvaluator);
            ReflectionTestUtils.setField(trafficService, "experimentAssignmentRepository", assignmentRepository);
            ReflectionTestUtils.setField(trafficService, "redisTemplate", redisTemplate);
            ReflectionTestUtils.setField(identityService, "redisTemplate", redisTemplate);

            ReflectionTestUtils.setField(dataService, "trafficService", trafficService);
            ReflectionTestUtils.setField(dataService, "configService", configService);
            ReflectionTestUtils.setField(dataService, "experimentAssignmentRepository", assignmentRepository);
            ReflectionTestUtils.setField(dataService, "experimentExposureRepository", exposureRepository);
            ReflectionTestUtils.setField(dataService, "experimentEventRepository", eventRepository);
            ReflectionTestUtils.setField(dataService, "eventInboxRepository", eventInboxRepository);
            ReflectionTestUtils.setField(eventInboxConsumer, "batchSize", 100);
            ReflectionTestUtils.setField(eventInboxConsumer, "maxRetryCount", 5);
            ReflectionTestUtils.setField(eventInboxConsumer, "lockMinutes", 5L);

            ReflectionTestUtils.setField(analysisService, "configService", configService);
            ReflectionTestUtils.setField(analysisService, "dataService", dataService);
            ReflectionTestUtils.setField(analysisService, "eventInboxRepository", eventInboxRepository);
            ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", eventReplayJobRepository);
            ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
            ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
            ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
            ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository",
                    eventMaterializationRepository);
            ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        }

        @SuppressWarnings("unchecked")
        private RedisTemplate<String, Object> buildRedisTemplate() {
            return mock(RedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        }
    }

    private static final class InMemoryAuditLogRepository implements AuditLogRepository {

        private final List<AuditLogRecord> records = new ArrayList<>();

        @Override
        public void save(AuditLogRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditLogRecord> listByResource(String resourceType, String resourceId, int limit) {
            List<AuditLogRecord> matchedRecords = new ArrayList<>();
            for (int index = records.size() - 1; index >= 0 && matchedRecords.size() < limit; index--) {
                AuditLogRecord record = records.get(index);
                if (resourceType.equals(record.getResourceType()) && resourceId.equals(record.getResourceId())) {
                    matchedRecords.add(record);
                }
            }
            return matchedRecords;
        }
    }

    private static final class InMemoryConfigService implements ConfigService {

        private final Map<String, ExperimentMetadata> configs = new LinkedHashMap<>();

        private final Map<String, ExperimentConfigDraftApproval> draftApprovals = new LinkedHashMap<>();

        @Override
        public void saveExperimentConfig(String experimentId, ExperimentMetadata metadata) {
            configs.put(experimentId, metadata);
        }

        @Override
        public ExperimentMetadata getExperimentConfig(String experimentId) {
            return configs.get(experimentId);
        }

        @Override
        public void deleteExperimentConfig(String experimentId) {
            configs.remove(experimentId);
        }

        @Override
        public List<String> getAllExperimentIds() {
            return new ArrayList<>(configs.keySet());
        }

        @Override
        public void addConfigChangeListener(String experimentId, Consumer<ExperimentMetadata> listener) {
        }

        @Override
        public long getExperimentConfigChangeSequence(String experimentId) {
            return 0L;
        }

        @Override
        public void waitForExperimentConfigChange(String experimentId, long knownChangeSequence, long waitMillis) {
        }

        @Override
        public ExperimentConfigVersion saveExperimentConfigVersion(String experimentId, ExperimentMetadata metadata,
                                                                  String publishedBy, String publishComment,
                                                                  Long sourceConfigVersion, String sourceType) {
            ExperimentConfigVersion version = new ExperimentConfigVersion();
            version.setExperimentId(experimentId);
            version.setConfigVersion(metadata.getConfigVersion());
            version.setMetadata(metadata);
            version.setPublishedBy(publishedBy);
            version.setPublishComment(publishComment);
            version.setSourceConfigVersion(sourceConfigVersion);
            version.setSourceType(sourceType);
            version.setPublishedAt(LocalDateTime.now());
            return version;
        }

        @Override
        public List<ExperimentConfigVersion> listExperimentConfigVersions(String experimentId) {
            return List.of();
        }

        @Override
        public Optional<ExperimentConfigVersion> getExperimentConfigVersion(String experimentId, long configVersion) {
            return Optional.empty();
        }

        @Override
        public ExperimentConfigDraft saveExperimentConfigDraft(String experimentId, ExperimentMetadata metadata,
                                                              long baseConfigVersion, String updatedBy,
                                                              String draftComment) {
            ExperimentConfigDraft draft = new ExperimentConfigDraft();
            draft.setExperimentId(experimentId);
            draft.setDraftVersion(1L);
            draft.setBaseConfigVersion(baseConfigVersion);
            draft.setMetadata(metadata);
            draft.setUpdatedBy(updatedBy);
            draft.setDraftComment(draftComment);
            draft.setUpdatedAt(LocalDateTime.now());
            return draft;
        }

        @Override
        public Optional<ExperimentConfigDraft> getExperimentConfigDraft(String experimentId) {
            return Optional.empty();
        }

        @Override
        public void deleteExperimentConfigDraft(String experimentId) {
        }

        @Override
        public ExperimentConfigDraftApproval saveExperimentConfigDraftApproval(ExperimentConfigDraftApproval approval) {
            draftApprovals.put(buildDraftApprovalKey(approval.getExperimentId(), approval.getDraftVersion()), approval);
            return approval;
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> getCurrentExperimentConfigDraftApproval(String experimentId) {
            return draftApprovals.values().stream()
                    .filter(approval -> experimentId.equals(approval.getExperimentId()))
                    .max((left, right) -> Long.compare(left.getDraftVersion(), right.getDraftVersion()));
        }

        @Override
        public List<ExperimentConfigDraftApproval> listExperimentConfigDraftApprovals(String experimentId) {
            return draftApprovals.values().stream()
                    .filter(approval -> experimentId.equals(approval.getExperimentId()))
                    .sorted((left, right) -> Long.compare(right.getDraftVersion(), left.getDraftVersion()))
                    .toList();
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> getExperimentConfigDraftApproval(String experimentId,
                                                                                       long draftVersion) {
            return Optional.ofNullable(draftApprovals.get(buildDraftApprovalKey(experimentId, draftVersion)));
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> updateExperimentConfigDraftApprovalStatus(
                String experimentId, long draftVersion, ExperimentMetadata.ApprovalStatus approvalStatus,
                String approvalOperator, String approvalComment) {
            return getExperimentConfigDraftApproval(experimentId, draftVersion)
                    .map(approval -> {
                        approval.setApprovalStatus(approvalStatus);
                        approval.setApprovalOperator(approvalOperator);
                        approval.setApprovalComment(approvalComment);
                        approval.setApprovalUpdatedAt(LocalDateTime.now());
                        return approval;
                    });
        }

        private String buildDraftApprovalKey(String experimentId, Long draftVersion) {
            return experimentId + ":" + draftVersion;
        }

        @Override
        public void saveLayerConfig(String layerId, ExperimentLayer layer) {
        }

        @Override
        public ExperimentLayer getLayerConfig(String layerId) {
            return null;
        }

        @Override
        public void deleteLayerConfig(String layerId) {
        }
    }

    private static final class InMemoryAssignmentRepository implements ExperimentAssignmentRepository {

        private final Map<String, ExperimentAssignment> assignments = new LinkedHashMap<>();

        @Override
        public void save(ExperimentAssignment assignment) {
            assignments.put(buildKey(assignment.getExperimentId(), assignment.getVisitorId()), assignment);
        }

        @Override
        public Optional<ExperimentAssignment> findByExperimentIdAndVisitorId(String experimentId, String visitorId) {
            return Optional.ofNullable(assignments.get(buildKey(experimentId, visitorId)));
        }

        @Override
        public long countByExperimentIdAndGroupId(String experimentId, String groupId) {
            return assignments.values().stream()
                    .filter(assignment -> experimentId.equals(assignment.getExperimentId()))
                    .filter(assignment -> groupId.equals(assignment.getGroupId()))
                    .count();
        }

        @Override
        public List<ExperimentAssignment> listByVisitorId(String visitorId) {
            return assignments.values().stream()
                    .filter(assignment -> visitorId.equals(assignment.getVisitorId()))
                    .toList();
        }

        private String buildKey(String experimentId, String visitorId) {
            return experimentId + ":" + visitorId;
        }
    }

    private static final class InMemoryExposureRepository implements ExperimentExposureRepository {

        private final List<ExperimentExposure> exposures = new ArrayList<>();

        @Override
        public void save(ExperimentExposure exposure) {
            saveIfAbsent(exposure);
        }

        @Override
        public boolean saveIfAbsent(ExperimentExposure exposure) {
            boolean exists = exposures.stream()
                    .anyMatch(current -> exposure.getIdempotencyKey().equals(current.getIdempotencyKey()));
            if (exists) {
                return false;
            }
            exposures.add(exposure);
            return true;
        }

        @Override
        public ExperimentExposure findByIdempotencyKey(String idempotencyKey) {
            return exposures.stream()
                    .filter(exposure -> idempotencyKey.equals(exposure.getIdempotencyKey()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public long countByExperimentIdAndGroupId(String experimentId, String groupId) {
            return listByExperimentIdAndGroupId(experimentId, groupId).size();
        }

        @Override
        public List<ExperimentExposure> listByExperimentIdAndGroupId(String experimentId, String groupId) {
            return exposures.stream()
                    .filter(exposure -> experimentId.equals(exposure.getExperimentId()))
                    .filter(exposure -> groupId.equals(exposure.getGroupId()))
                    .toList();
        }

        @Override
        public List<ExperimentExposure> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
                                                                                LocalDateTime startTime,
                                                                                LocalDateTime endTime) {
            return listByExperimentIdAndGroupId(experimentId, groupId).stream()
                    .filter(exposure -> !exposure.getExposedAt().isBefore(startTime))
                    .filter(exposure -> !exposure.getExposedAt().isAfter(endTime))
                    .toList();
        }

        @Override
        public long countByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                       LocalDateTime endTime) {
            return listByReplayScope(experimentId, groupId, startTime, endTime).size();
        }

        @Override
        public List<ExperimentExposure> listByReplayScope(String experimentId, String groupId,
                                                          LocalDateTime startTime, LocalDateTime endTime) {
            return listByExperimentIdAndGroupId(experimentId, groupId).stream()
                    .filter(exposure -> startTime == null || !exposure.getExposedAt().isBefore(startTime))
                    .filter(exposure -> endTime == null || !exposure.getExposedAt().isAfter(endTime))
                    .toList();
        }

        @Override
        public List<ExperimentExposure> listUnmaterializedByReplayScope(String experimentId, String groupId,
                                                                        LocalDateTime startTime,
                                                                        LocalDateTime endTime) {
            return listByReplayScope(experimentId, groupId, startTime, endTime);
        }
    }

    private static final class InMemoryEventInboxRepository implements EventInboxRepository {

        private final List<EventInboxRecord> records = new ArrayList<>();
        private final Map<String, EventInboxRecord> recordsByIdempotencyKey = new LinkedHashMap<>();

        @Override
        public boolean saveIfAbsent(EventInboxRecord record) {
            if (recordsByIdempotencyKey.containsKey(record.getIdempotencyKey())) {
                return false;
            }
            records.add(record);
            recordsByIdempotencyKey.put(record.getIdempotencyKey(), record);
            return true;
        }

        @Override
        public List<EventInboxRecord> listDueRecords(LocalDateTime now, int limit) {
            return records.stream()
                    .filter(record -> isDue(record, now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EventInboxRecord> listDueRecords(String experimentId, LocalDateTime now, int limit) {
            return records.stream()
                    .filter(record -> experimentId.equals(record.getExperimentId()))
                    .filter(record -> isDue(record, now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean markProcessing(String inboxId, String lockedBy, LocalDateTime now, LocalDateTime lockedUntil) {
            EventInboxRecord record = findByInboxId(inboxId);
            if (record == null) {
                return false;
            }
            record.setStatus(EventInboxConstants.STATUS_PROCESSING);
            record.setLockedBy(lockedBy);
            record.setLockedUntil(lockedUntil);
            return true;
        }

        @Override
        public void markDone(String inboxId, LocalDateTime processedAt) {
            EventInboxRecord record = findByInboxId(inboxId);
            record.setStatus(EventInboxConstants.STATUS_DONE);
            record.setProcessedAt(processedAt);
            record.setLockedBy(null);
            record.setLockedUntil(null);
        }

        @Override
        public void markRetry(String inboxId, int retryCount, LocalDateTime nextRetryAt, String lastError) {
            EventInboxRecord record = findByInboxId(inboxId);
            record.setStatus(EventInboxConstants.STATUS_RETRY);
            record.setRetryCount(retryCount);
            record.setNextRetryAt(nextRetryAt);
            record.setLastError(lastError);
            record.setLockedBy(null);
            record.setLockedUntil(null);
        }

        @Override
        public void markDead(String inboxId, int retryCount, String lastError, LocalDateTime processedAt) {
            EventInboxRecord record = findByInboxId(inboxId);
            record.setStatus(EventInboxConstants.STATUS_DEAD);
            record.setRetryCount(retryCount);
            record.setLastError(lastError);
            record.setProcessedAt(processedAt);
            record.setLockedBy(null);
            record.setLockedUntil(null);
        }

        @Override
        public List<EventInboxStatusCountEntity> countByExperimentIdGroupByStatus(String experimentId) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (EventInboxRecord record : records) {
                if (!experimentId.equals(record.getExperimentId())) {
                    continue;
                }
                counts.merge(record.getStatus(), 1L, Long::sum);
            }

            List<EventInboxStatusCountEntity> countEntities = new ArrayList<>();
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                EventInboxStatusCountEntity countEntity = new EventInboxStatusCountEntity();
                countEntity.setStatus(entry.getKey());
                countEntity.setEventCount(entry.getValue());
                countEntities.add(countEntity);
            }
            return countEntities;
        }

        @Override
        public LocalDateTime selectOldestUnfinishedAcceptedAt(String experimentId) {
            LocalDateTime oldestAcceptedAt = null;
            for (EventInboxRecord record : records) {
                if (!experimentId.equals(record.getExperimentId()) || !isUnfinished(record.getStatus())
                        || record.getAcceptedAt() == null) {
                    continue;
                }
                if (oldestAcceptedAt == null || record.getAcceptedAt().isBefore(oldestAcceptedAt)) {
                    oldestAcceptedAt = record.getAcceptedAt();
                }
            }
            return oldestAcceptedAt;
        }

        @Override
        public int retryDeadRecords(String experimentId, LocalDateTime nextRetryAt) {
            int affectedCount = 0;
            for (EventInboxRecord record : records) {
                if (!experimentId.equals(record.getExperimentId())
                        || !EventInboxConstants.STATUS_DEAD.equals(record.getStatus())) {
                    continue;
                }
                record.setStatus(EventInboxConstants.STATUS_RETRY);
                record.setRetryCount(0);
                record.setNextRetryAt(nextRetryAt);
                record.setLockedBy(null);
                record.setLockedUntil(null);
                record.setProcessedAt(null);
                affectedCount++;
            }
            return affectedCount;
        }

        private boolean isUnfinished(String status) {
            return EventInboxConstants.STATUS_PENDING.equals(status)
                    || EventInboxConstants.STATUS_PROCESSING.equals(status)
                    || EventInboxConstants.STATUS_RETRY.equals(status);
        }

        private boolean isDue(EventInboxRecord record, LocalDateTime now) {
            boolean readyForProcessing = (EventInboxConstants.STATUS_PENDING.equals(record.getStatus())
                    || EventInboxConstants.STATUS_RETRY.equals(record.getStatus()))
                    && !record.getNextRetryAt().isAfter(now);
            boolean expiredProcessingLock = EventInboxConstants.STATUS_PROCESSING.equals(record.getStatus())
                    && record.getLockedUntil() != null
                    && record.getLockedUntil().isBefore(now);
            return readyForProcessing || expiredProcessingLock;
        }

        private EventInboxRecord findByInboxId(String inboxId) {
            return records.stream()
                    .filter(record -> inboxId.equals(record.getInboxId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static final class InMemoryEventMaterializationRepository implements EventMaterializationRepository {

        private final Map<String, EventMaterializationRecord> records = new LinkedHashMap<>();

        @Override
        public void saveOrRefresh(EventMaterializationRecord record) {
            records.put(buildKey(record.getFactKind(), record.getFactId()), record);
        }

        @Override
        public boolean exists(String factKind, String factId) {
            return records.containsKey(buildKey(factKind, factId));
        }

        @Override
        public long countMaterializedEventsByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                                         LocalDateTime endTime, List<String> eventTypes) {
            return records.values().stream()
                    .filter(record -> EventMaterializationRecord.FACT_KIND_EVENT.equals(record.getFactKind()))
                    .filter(record -> experimentId.equals(record.getExperimentId()))
                    .filter(record -> groupId.equals(record.getGroupId()))
                    .filter(record -> eventTypes == null || eventTypes.isEmpty()
                            || eventTypes.contains(record.getEventType()))
                    .count();
        }

        @Override
        public long countMaterializedExposuresByReplayScope(String experimentId, String groupId,
                                                            LocalDateTime startTime, LocalDateTime endTime) {
            return records.values().stream()
                    .filter(record -> EventMaterializationRecord.FACT_KIND_EXPOSURE.equals(record.getFactKind()))
                    .filter(record -> experimentId.equals(record.getExperimentId()))
                    .filter(record -> groupId.equals(record.getGroupId()))
                    .count();
        }

        private String buildKey(String factKind, String factId) {
            return factKind + ":" + factId;
        }
    }

    private static final class InMemoryEventReplayJobRepository implements EventReplayJobRepository {

        private final Map<String, EventReplayJobRecord> recordsByJobId = new LinkedHashMap<>();

        @Override
        public int expireStaleRunningJobs(String experimentId, LocalDateTime staleBefore, LocalDateTime finishedAt,
                                          String errorMessage) {
            int expiredCount = 0;
            for (EventReplayJobRecord record : recordsByJobId.values()) {
                if (experimentId.equals(record.getExperimentId())
                        && (EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())
                        || EventReplayJobRecord.STATUS_CANCEL_REQUESTED.equals(record.getJobStatus()))
                        && record.getStartedAt().isBefore(staleBefore)) {
                    record.setJobStatus(EventReplayJobRecord.STATUS_FAILED);
                    record.setActiveKey(null);
                    record.setErrorMessage(errorMessage);
                    record.setFinishedAt(finishedAt);
                    expiredCount++;
                }
            }
            return expiredCount;
        }

        @Override
        public boolean createRunningJob(EventReplayJobRecord record) {
            boolean hasActiveJob = recordsByJobId.values().stream()
                    .anyMatch(existing -> record.getExperimentId().equals(existing.getActiveKey()));
            if (hasActiveJob) {
                return false;
            }
            recordsByJobId.put(record.getReplayJobId(), record);
            return true;
        }

        @Override
        public List<EventReplayJobRecord> listRecentByExperimentId(String experimentId, int limit) {
            List<EventReplayJobRecord> matchedRecords = recordsByJobId.values().stream()
                    .filter(record -> experimentId.equals(record.getExperimentId()))
                    .toList();
            List<EventReplayJobRecord> recentRecords = new ArrayList<>(matchedRecords);
            java.util.Collections.reverse(recentRecords);
            return recentRecords.stream()
                    .limit(Math.max(1, limit))
                    .toList();
        }

        @Override
        public EventReplayJobRecord findByExperimentIdAndReplayJobId(String experimentId, String replayJobId) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null || !experimentId.equals(record.getExperimentId())) {
                return null;
            }
            return record;
        }

        @Override
        public boolean updateProgress(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                                      long groupCount, long mabRewardCount) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null || !EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())) {
                return false;
            }
            record.setAffectedCount(affectedCount);
            record.setEventCount(eventCount);
            record.setExposureCount(exposureCount);
            record.setGroupCount(groupCount);
            record.setMabRewardCount(mabRewardCount);
            return true;
        }

        @Override
        public boolean markSucceeded(String replayJobId, long affectedCount, long eventCount, long exposureCount,
                                     long groupCount, long mabRewardCount, LocalDateTime finishedAt) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null) {
                return false;
            }
            if (!EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())) {
                return false;
            }
            record.setJobStatus(EventReplayJobRecord.STATUS_SUCCEEDED);
            record.setActiveKey(null);
            record.setAffectedCount(affectedCount);
            record.setEventCount(eventCount);
            record.setExposureCount(exposureCount);
            record.setGroupCount(groupCount);
            record.setMabRewardCount(mabRewardCount);
            record.setFinishedAt(finishedAt);
            return true;
        }

        @Override
        public boolean markFailed(String replayJobId, String errorMessage, LocalDateTime finishedAt) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null) {
                return false;
            }
            if (!EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())
                    && !EventReplayJobRecord.STATUS_CANCEL_REQUESTED.equals(record.getJobStatus())) {
                return false;
            }
            record.setJobStatus(EventReplayJobRecord.STATUS_FAILED);
            record.setActiveKey(null);
            record.setErrorMessage(errorMessage);
            record.setFinishedAt(finishedAt);
            return true;
        }

        @Override
        public boolean requestCancellation(String replayJobId, String errorMessage) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null || !EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())) {
                return false;
            }
            record.setJobStatus(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
            record.setErrorMessage(errorMessage);
            return true;
        }

        @Override
        public boolean markCancelled(String replayJobId, String errorMessage, LocalDateTime finishedAt) {
            EventReplayJobRecord record = recordsByJobId.get(replayJobId);
            if (record == null || (!EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())
                    && !EventReplayJobRecord.STATUS_CANCEL_REQUESTED.equals(record.getJobStatus()))) {
                return false;
            }
            record.setJobStatus(EventReplayJobRecord.STATUS_CANCELLED);
            record.setActiveKey(null);
            record.setErrorMessage(errorMessage);
            record.setFinishedAt(finishedAt);
            return true;
        }
    }

    private static final class InMemoryEventRepository implements ExperimentEventRepository {

        private final List<ExperimentEventFact> events = new ArrayList<>();
        private final Map<String, ExperimentEventFact> eventsByClientEventId = new LinkedHashMap<>();

        @Override
        public boolean saveIfAbsent(ExperimentEventFact eventFact) {
            if (eventFact.getClientEventId() != null && !eventFact.getClientEventId().isBlank()) {
                String idempotencyKey = eventFact.getExperimentId() + ":" + eventFact.getClientEventId();
                if (eventsByClientEventId.containsKey(idempotencyKey)) {
                    return false;
                }
                eventsByClientEventId.put(idempotencyKey, eventFact);
            }
            events.add(eventFact);
            return true;
        }

        @Override
        public ExperimentEventFact findByExperimentIdAndClientEventId(String experimentId, String clientEventId) {
            return eventsByClientEventId.get(experimentId + ":" + clientEventId);
        }

        @Override
        public long countByExperimentIdAndGroupIdAndEventType(String experimentId, String groupId, String eventType) {
            return events.stream()
                    .filter(event -> experimentId.equals(event.getExperimentId()))
                    .filter(event -> groupId.equals(event.getGroupId()))
                    .filter(event -> eventType.equals(event.getEventType()))
                    .count();
        }

        @Override
        public long countDistinctVisitorByExperimentIdAndGroupId(String experimentId, String groupId) {
            return events.stream()
                    .filter(event -> experimentId.equals(event.getExperimentId()))
                    .filter(event -> groupId.equals(event.getGroupId()))
                    .map(ExperimentEventFact::getVisitorId)
                    .distinct()
                    .count();
        }

        @Override
        public List<ExperimentEventFact> listByExperimentIdAndGroupId(String experimentId, String groupId) {
            return events.stream()
                    .filter(event -> experimentId.equals(event.getExperimentId()))
                    .filter(event -> groupId.equals(event.getGroupId()))
                    .toList();
        }

        @Override
        public List<ExperimentEventFact> listByExperimentIdAndGroupIdInTimeRange(String experimentId, String groupId,
                                                                                 LocalDateTime startTime,
                                                                                 LocalDateTime endTime) {
            return listByExperimentIdAndGroupId(experimentId, groupId).stream()
                    .filter(event -> !event.getEventTime().isBefore(startTime))
                    .filter(event -> !event.getEventTime().isAfter(endTime))
                    .toList();
        }

        @Override
        public long countByReplayScope(String experimentId, String groupId, LocalDateTime startTime,
                                       LocalDateTime endTime, List<String> eventTypes) {
            return listByReplayScope(experimentId, groupId, startTime, endTime, eventTypes).size();
        }

        @Override
        public List<ExperimentEventFact> listByReplayScope(String experimentId, String groupId,
                                                           LocalDateTime startTime, LocalDateTime endTime,
                                                           List<String> eventTypes) {
            return listByExperimentIdAndGroupId(experimentId, groupId).stream()
                    .filter(event -> startTime == null || !event.getEventTime().isBefore(startTime))
                    .filter(event -> endTime == null || !event.getEventTime().isAfter(endTime))
                    .filter(event -> eventTypes == null || eventTypes.isEmpty()
                            || eventTypes.contains(event.getEventType()))
                    .toList();
        }

        @Override
        public List<ExperimentEventFact> listUnmaterializedByReplayScope(String experimentId, String groupId,
                                                                         LocalDateTime startTime,
                                                                         LocalDateTime endTime,
                                                                         List<String> eventTypes) {
            return listByReplayScope(experimentId, groupId, startTime, endTime, eventTypes);
        }
    }
}
