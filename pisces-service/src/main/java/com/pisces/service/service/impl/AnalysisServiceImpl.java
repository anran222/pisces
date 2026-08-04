package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.EventReplayPlanRequest;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.common.enums.ResponseCode;
import com.pisces.common.response.EventPipelineOperationResponse;
import com.pisces.common.response.EventReplayPlanResponse;
import com.pisces.common.response.EventPipelineStatusResponse;
import com.pisces.common.response.EventReplayJobResponse;
import com.pisces.service.ai.TongYiTextGenerationClient;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.entity.EventInboxStatusCountEntity;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventPipelineRebuildResult;
import com.pisces.service.event.EventReplayJobRecord;
import com.pisces.service.event.EventReplayProgressReporter;
import com.pisces.service.metrics.EventReplayMetrics;
import com.pisces.service.repository.EventInboxRepository;
import com.pisces.service.repository.EventMaterializationRepository;
import com.pisces.service.repository.EventReplayJobRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.repository.ExperimentReportSnapshotRepository;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.util.StatisticalUtils;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 数据分析服务实现
 */
@Slf4j
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private static final double DEFAULT_GATE_MDE = 0.05;

    private static final double DEFAULT_GATE_ALPHA = 0.05;

    private static final double DEFAULT_GATE_POWER = 0.80;

    private static final long MAX_HEALTHY_PENDING_SECONDS = 300L;

    private static final String PIPELINE_STATUS_NO_DATA = "NO_DATA";

    private static final String PIPELINE_STATUS_PENDING = "PENDING";

    private static final String PIPELINE_STATUS_RETRY = "RETRY";

    private static final String PIPELINE_STATUS_DEAD = "DEAD";

    private static final String PIPELINE_STATUS_REJECTED = "REJECTED";

    private static final String PIPELINE_STATUS_DONE = "DONE";

    private static final String EVENT_PIPELINE_OPERATION_RETRY_DEAD = "RETRY_DEAD";

    private static final String EVENT_PIPELINE_OPERATION_DRAIN = "DRAIN_INBOX";

    private static final String EVENT_PIPELINE_OPERATION_REPLAY = "REPLAY_DERIVED";

    private static final String EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION = "REPAIR_MATERIALIZATION";

    private static final String EVENT_PIPELINE_OPERATION_CANCEL_REPLAY_JOB = "CANCEL_REPLAY_JOB";

    private static final String EVENT_PIPELINE_OPERATION_SUCCESS = "SUCCESS";

    private static final String EVENT_PIPELINE_OPERATION_PARTIAL = "PARTIAL";

    private static final String EVENT_PIPELINE_OPERATION_RUNNING = "RUNNING";

    private static final String EVENT_PIPELINE_OPERATION_CANCELLED = "CANCELLED";

    private static final String REPLAY_JOB_ID_PREFIX = "replay_";

    private static final int MAX_REPLAY_JOB_QUERY_LIMIT = 50;

    private static final int MAX_REPLAY_PLAN_SEGMENT_COUNT = 48;

    private static final String REPLAY_JOB_CANCEL_REQUESTED_MESSAGE = "事件重放任务取消已受理";

    private static final String REPLAY_JOB_CANCELLED_MESSAGE = "事件重放任务已取消";

    private static final String REPLAY_MODE_FULL_DERIVED_REBUILD = "FULL_DERIVED_REBUILD";

    private static final String REPLAY_MODE_FILTERED_DERIVED_COPY = "FILTERED_DERIVED_COPY_REPLAY";

    private static final String REPLAY_SEGMENT_ACTION_NONE = "NONE";

    private static final String REPLAY_SEGMENT_ACTION_REPAIR_MATERIALIZATION = "REPAIR_MATERIALIZATION_SEGMENT";
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private DataService dataService;
    
    @Autowired
    private BayesianAnalysisService bayesianAnalysisService;
    
    @Autowired
    private CausalInferenceService causalInferenceService;
    
    @Autowired
    private TongYiConfig tongYiConfig;

    @Autowired
    private TongYiTextGenerationClient tongYiTextGenerationClient;

    @Autowired
    private ExperimentReportSnapshotRepository experimentReportSnapshotRepository;

    @Autowired
    private EventInboxRepository eventInboxRepository;

    @Autowired
    private EventInboxConsumer eventInboxConsumer;

    @Autowired
    private EventInboxMaterializer eventInboxMaterializer;

    @Autowired
    private EventReplayJobRepository eventReplayJobRepository;

    @Autowired
    private EventMaterializationRepository eventMaterializationRepository;

    @Autowired
    private ExperimentEventRepository experimentEventRepository;

    @Autowired
    private ExperimentExposureRepository experimentExposureRepository;

    @Value("${pisces.event-pipeline.drain.max-rounds:100}")
    private int eventPipelineDrainMaxRounds;

    @Value("${pisces.event-pipeline.drain.max-wait-ms:5000}")
    private long eventPipelineDrainMaxWaitMs;

    @Value("${pisces.event-pipeline.drain.idle-wait-ms:50}")
    private long eventPipelineDrainIdleWaitMs;

    @Value("${pisces.event-pipeline.replay.job-timeout-minutes:30}")
    private long eventPipelineReplayJobTimeoutMinutes;

    @Value("${pisces.event-pipeline.replay.max-filtered-copy-facts:50000}")
    private long eventPipelineReplayMaxFilteredCopyFacts;

    @Resource(name = "eventReplayTaskExecutor")
    private Executor eventReplayTaskExecutor = Runnable::run;

    @Autowired(required = false)
    private EventReplayMetrics eventReplayMetrics;

    @Resource
    private ObjectProvider<AIDecisionService> aiDecisionServiceProvider;
    
    /**
     * 获取实验统计数据
     */
    @Override
    public Statistics getStatistics(String experimentId) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            return null;
        }
        
        Statistics statistics = new Statistics();
        statistics.setExperimentId(experimentId);
        statistics.setExperimentName(metadata.getExperiment().getName());
        statistics.setExperimentStatus(metadata.getExperiment().getStatus().name());
        statistics.setStatisticsStartTime(metadata.getExperiment().getStartTime());
        statistics.setStatisticsEndTime(java.time.LocalDateTime.now());
        
        Map<String, Statistics.GroupStatistics> groupStatsMap = new LinkedHashMap<>();
        List<MetricDefinition> metricDefinitions = resolveMetricDefinitions(metadata);
        MetricDefinition primaryMetricDefinition = resolvePrimaryMetric(metricDefinitions);
        
        // 用于计算总览的变量
        long totalVisitors = 0;
        long totalEvents = 0;
        long totalAssignments = 0;
        long totalExposures = 0;
        double bestConversionRate = 0.0;
        double bestPrimaryMetricValue = Double.NEGATIVE_INFINITY;
        String bestPerformingGroup = null;
        
        // 确定基准组
        String baselineGroupId = resolveBaselineGroupId(metadata);
        double baselineConversionRate = 0.0;
        
        // 遍历所有实验组计算基础统计
        if (metadata.getGroups() != null) {
            for (Map.Entry<String, com.pisces.common.model.ExperimentGroup> entry : metadata.getGroups().entrySet()) {
                String groupId = entry.getKey();
                com.pisces.common.model.ExperimentGroup group = entry.getValue();
                
                Statistics.GroupStatistics groupStats = calculateGroupStatistics(
                        experimentId, groupId, group, baselineGroupId, metadata.getEventDefinitions(), metricDefinitions);
                long assignmentCount = dataService.getAssignmentCount(experimentId, groupId);
                long exposureCount = dataService.getExposureCount(experimentId, groupId);
                groupStats.setAssignmentCount(assignmentCount);
                groupStats.setExposureCount(exposureCount);
                groupStatsMap.put(groupId, groupStats);
                
                // 累计总访客和事件
                totalVisitors += groupStats.getUserCount() != null ? groupStats.getUserCount() : 0;
                totalAssignments += assignmentCount;
                totalExposures += exposureCount;
                if (groupStats.getEventCounts() != null) {
                    for (Long count : groupStats.getEventCounts().values()) {
                        totalEvents += count != null ? count : 0;
                    }
                }
                
                // 记录基准组转化率
                if (groupId.equals(baselineGroupId)) {
                    baselineConversionRate = groupStats.getConversionRate() != null ? 
                            groupStats.getConversionRate() : 0.0;
                }
                
                // 找出最佳表现组
                Double conversionRate = groupStats.getConversionRate();
                if (conversionRate != null && conversionRate > bestConversionRate) {
                    bestConversionRate = conversionRate;
                }

                double primaryMetricValue = extractPrimaryMetricValue(groupStats, primaryMetricDefinition);
                if (primaryMetricValue > bestPrimaryMetricValue) {
                    bestPrimaryMetricValue = primaryMetricValue;
                    bestPerformingGroup = groupId;
                }
            }
            
            // 第二次遍历：计算提升率
            for (Map.Entry<String, Statistics.GroupStatistics> entry : groupStatsMap.entrySet()) {
                Statistics.GroupStatistics groupStats = entry.getValue();
                if (!entry.getKey().equals(baselineGroupId) && baselineConversionRate > 0) {
                    Double conversionRate = groupStats.getConversionRate();
                    if (conversionRate != null) {
                        double lift = (conversionRate - baselineConversionRate) / baselineConversionRate;
                        groupStats.setLiftRate(lift);
                    }
                }
            }
        }
        
        statistics.setGroupStatistics(groupStatsMap);
        
        // 设置总览统计
        Statistics.ExperimentSummary summary = new Statistics.ExperimentSummary();
        summary.setTotalVisitors(totalVisitors);
        summary.setTotalEvents(totalEvents);
        summary.setTotalAssignments(totalAssignments);
        summary.setTotalExposures(totalExposures);
        summary.setBestPerformingGroup(bestPerformingGroup);
        summary.setBestConversionRate(bestConversionRate);
        summary.setPrimaryMetricKey(primaryMetricDefinition != null ? primaryMetricDefinition.getKey() : null);
        summary.setBestPrimaryMetricValue(bestPrimaryMetricValue == Double.NEGATIVE_INFINITY ? null : bestPrimaryMetricValue);
        summary.setBreachedGuardrails(resolveBreachedGuardrails(groupStatsMap, metricDefinitions, baselineGroupId,
                bestPerformingGroup));
        
        // 计算总体转化率和点击率
        long totalViews = 0;
        long totalClicks = 0;
        long totalConversions = 0;
        for (Statistics.GroupStatistics gs : groupStatsMap.values()) {
            Long views = gs.getViewCount();
            Long clicks = gs.getClickCount();
            Long conversions = gs.getConversionCount();
            totalViews += views != null ? views : 0;
            totalClicks += clicks != null ? clicks : 0;
            totalConversions += conversions != null ? conversions : 0;
        }
        summary.setOverallClickRate(totalViews > 0 ? (double) totalClicks / totalViews : 0.0);
        summary.setOverallConversionRate(totalViews > 0 ? (double) totalConversions / totalViews : 0.0);
        
        statistics.setSummary(summary);
        statistics.setDataQualityCheck(buildDataQualityCheck(experimentId, metadata, groupStatsMap, baselineGroupId));
        
        return statistics;
    }

    @Override
    public EventPipelineStatusResponse getEventPipelineStatus(String experimentId) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        LocalDateTime generatedAt = LocalDateTime.now();
        Map<String, Long> statusCounts = buildEventInboxStatusCounts(experimentId);
        long pendingCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_PENDING);
        long processingCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_PROCESSING);
        long retryCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_RETRY);
        long doneCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_DONE);
        long deadCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_DEAD);
        long rejectedCount = getStatusCount(statusCounts, EventInboxConstants.STATUS_REJECTED);
        long totalCount = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        long unfinishedCount = pendingCount + processingCount + retryCount;
        long maxPendingSeconds = resolveMaxPendingSeconds(experimentId, generatedAt, unfinishedCount);
        String status = resolvePipelineStatus(totalCount, pendingCount, processingCount, retryCount, deadCount,
                rejectedCount);

        EventPipelineStatusResponse response = new EventPipelineStatusResponse();
        response.setExperimentId(experimentId);
        response.setTotalCount(totalCount);
        response.setPendingCount(pendingCount);
        response.setProcessingCount(processingCount);
        response.setRetryCount(retryCount);
        response.setDoneCount(doneCount);
        response.setDeadCount(deadCount);
        response.setRejectedCount(rejectedCount);
        response.setUnfinishedCount(unfinishedCount);
        response.setMaxPendingSeconds(maxPendingSeconds);
        response.setHealthy(isPipelineHealthy(retryCount, deadCount, rejectedCount, maxPendingSeconds));
        response.setStatus(status);
        response.setGeneratedAt(generatedAt);
        return response;
    }

    @Override
    public EventPipelineOperationResponse retryDeadEvents(String experimentId, String operator) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        LocalDateTime operatedAt = LocalDateTime.now();
        int affectedCount = eventInboxRepository.retryDeadRecords(experimentId, operatedAt);
        return buildEventPipelineOperationResponse(
                experimentId,
                EVENT_PIPELINE_OPERATION_RETRY_DEAD,
                operator,
                operatedAt,
                affectedCount,
                0L,
                0L,
                0L,
                0L,
                "死信事件已重新投递，后台消费者会按正常重试流程处理");
    }

    @Override
    public EventPipelineOperationResponse drainEventPipeline(String experimentId, String operator) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        LocalDateTime operatedAt = LocalDateTime.now();
        long affectedCount = 0L;
        int rounds = 0;
        int maxRounds = Math.max(1, eventPipelineDrainMaxRounds);
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, eventPipelineDrainMaxWaitMs));
        EventPipelineStatusResponse pipelineStatus = getEventPipelineStatus(experimentId);

        while (resolveLong(pipelineStatus.getUnfinishedCount()) > 0L && rounds < maxRounds) {
            int processedCount = eventInboxConsumer.processDueRecords(experimentId);
            affectedCount += processedCount;
            rounds++;
            pipelineStatus = getEventPipelineStatus(experimentId);
            if (resolveLong(pipelineStatus.getUnfinishedCount()) == 0L) {
                break;
            }
            if (processedCount == 0) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                waitBeforeNextDrainPoll();
            }
        }

        long unfinishedCount = resolveLong(pipelineStatus.getUnfinishedCount());
        boolean drained = unfinishedCount == 0L;
        String message = drained
                ? "已同步物化实验事件管道待处理记录"
                : "事件管道仍有未完成 inbox 记录: unfinishedCount=" + unfinishedCount;
        return buildEventPipelineOperationResponse(
                experimentId,
                EVENT_PIPELINE_OPERATION_DRAIN,
                operator,
                operatedAt,
                affectedCount,
                0L,
                0L,
                0L,
                0L,
                drained ? EVENT_PIPELINE_OPERATION_SUCCESS : EVENT_PIPELINE_OPERATION_PARTIAL,
                message);
    }

    @Override
    public EventPipelineOperationResponse replayEventPipeline(String experimentId, String operator) {
        return replayEventPipeline(experimentId, null, operator);
    }

    @Override
    public EventPipelineOperationResponse replayEventPipeline(String experimentId, EventReplayPlanRequest request,
                                                              String operator) {
        EventReplayPlanResponse replayPlan = planEventReplay(experimentId, request);
        EventReplayScope replayScope = buildReplayScope(request);
        validateFilteredCopyReplayPlan(replayScope, replayPlan);
        LocalDateTime operatedAt = LocalDateTime.now();
        String normalizedOperator = StringUtils.hasText(operator) ? operator : "system";
        EventReplayJobRecord replayJob = startReplayJob(experimentId, normalizedOperator, operatedAt, replayScope,
                replayPlan, false);
        submitReplayJob(replayJob);
        String message = replayScope.fullDerivedReplay()
                ? "已提交全量派生重放任务，请通过 replayJobId 查询进度"
                : "已提交筛选范围复制型重放任务，请通过 replayJobId 查询进度";
        EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                experimentId,
                EVENT_PIPELINE_OPERATION_REPLAY,
                normalizedOperator,
                operatedAt,
                0L,
                0L,
                0L,
                0L,
                0L,
                EVENT_PIPELINE_OPERATION_RUNNING,
                message);
        response.setReplayJobId(replayJob.getReplayJobId());
        response.setReplayJobStatus(EventReplayJobRecord.STATUS_RUNNING);
        applyReplayScope(response, replayJob);
        return response;
    }

    @Override
    public EventReplayPlanResponse planEventReplay(String experimentId, EventReplayPlanRequest request) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadataOrThrow(experimentId);
        EventReplayScope replayScope = buildReplayScope(request);
        ReplayPlanCounts counts = calculateReplayPlanCounts(metadata, experimentId, replayScope);

        EventReplayPlanResponse response = buildReplayPlanResponse(experimentId, replayScope, counts);
        applyReplayPlanSegments(response, metadata, experimentId, replayScope, request);
        response.setGeneratedAt(LocalDateTime.now());
        return response;
    }

    private ReplayPlanCounts calculateReplayPlanCounts(ExperimentMetadata metadata, String experimentId,
                                                       EventReplayScope replayScope) {
        long totalEventCount = 0L;
        long totalExposureCount = 0L;
        long totalMaterializedEventCount = 0L;
        long totalMaterializedExposureCount = 0L;
        List<EventReplayPlanResponse.GroupReplayPlan> groups = new ArrayList<>();
        if (metadata.getGroups() != null) {
            for (Map.Entry<String, ExperimentGroup> entry : metadata.getGroups().entrySet()) {
                String groupId = entry.getKey();
                ExperimentGroup group = entry.getValue();
                long eventCount = replayScope.includeEvents()
                        ? experimentEventRepository.countByReplayScope(experimentId, groupId,
                                replayScope.startTime(), replayScope.endTime(), replayScope.eventTypes())
                        : 0L;
                long exposureCount = replayScope.includeExposures()
                        ? experimentExposureRepository.countByReplayScope(experimentId, groupId,
                                replayScope.startTime(), replayScope.endTime())
                        : 0L;
                long materializedEventCount = replayScope.includeEvents()
                        ? clampMaterializedCount(eventCount,
                                eventMaterializationRepository.countMaterializedEventsByReplayScope(experimentId,
                                        groupId, replayScope.startTime(), replayScope.endTime(),
                                        replayScope.eventTypes()))
                        : 0L;
                long materializedExposureCount = replayScope.includeExposures()
                        ? clampMaterializedCount(exposureCount,
                                eventMaterializationRepository.countMaterializedExposuresByReplayScope(experimentId,
                                        groupId, replayScope.startTime(), replayScope.endTime()))
                        : 0L;
                long unmaterializedEventCount = eventCount - materializedEventCount;
                long unmaterializedExposureCount = exposureCount - materializedExposureCount;
                totalEventCount += eventCount;
                totalExposureCount += exposureCount;
                totalMaterializedEventCount += materializedEventCount;
                totalMaterializedExposureCount += materializedExposureCount;

                EventReplayPlanResponse.GroupReplayPlan groupPlan = new EventReplayPlanResponse.GroupReplayPlan();
                groupPlan.setGroupId(groupId);
                groupPlan.setGroupName(group == null || !StringUtils.hasText(group.getName())
                        ? groupId
                        : group.getName());
                groupPlan.setEventCount(eventCount);
                groupPlan.setMaterializedEventCount(materializedEventCount);
                groupPlan.setUnmaterializedEventCount(unmaterializedEventCount);
                groupPlan.setExposureCount(exposureCount);
                groupPlan.setMaterializedExposureCount(materializedExposureCount);
                groupPlan.setUnmaterializedExposureCount(unmaterializedExposureCount);
                groupPlan.setAffectedCount(eventCount + exposureCount);
                groupPlan.setMaterializedCount(materializedEventCount + materializedExposureCount);
                groupPlan.setUnmaterializedCount(unmaterializedEventCount + unmaterializedExposureCount);
                groups.add(groupPlan);
            }
        }

        return new ReplayPlanCounts(
                totalEventCount,
                totalExposureCount,
                totalMaterializedEventCount,
                totalMaterializedExposureCount,
                groups);
    }

    private EventReplayPlanResponse buildReplayPlanResponse(String experimentId, EventReplayScope replayScope,
                                                            ReplayPlanCounts counts) {
        EventReplayPlanResponse response = new EventReplayPlanResponse();
        response.setExperimentId(experimentId);
        response.setStartTime(replayScope.startTime());
        response.setEndTime(replayScope.endTime());
        response.setEventTypes(replayScope.eventTypes());
        response.setIncludeEvents(replayScope.includeEvents());
        response.setIncludeExposures(replayScope.includeExposures());
        response.setFullDerivedReplay(replayScope.fullDerivedReplay());
        response.setReplayMode(replayScope.replayMode());
        response.setMessage(replayScope.fullDerivedReplay()
                ? "当前计划等价于全量派生重放，可使用 /events/replay 执行"
                : "当前计划会执行筛选范围复制型 replay，不会清空 Redis/MAB 派生数据");
        applyReplayPlanCounts(response, counts);
        return response;
    }

    private void applyReplayPlanCounts(EventReplayPlanResponse response, ReplayPlanCounts counts) {
        long totalUnmaterializedEventCount = counts.eventCount() - counts.materializedEventCount();
        long totalUnmaterializedExposureCount = counts.exposureCount() - counts.materializedExposureCount();
        response.setGroupCount((long) counts.groups().size());
        response.setEventCount(counts.eventCount());
        response.setMaterializedEventCount(counts.materializedEventCount());
        response.setUnmaterializedEventCount(totalUnmaterializedEventCount);
        response.setExposureCount(counts.exposureCount());
        response.setMaterializedExposureCount(counts.materializedExposureCount());
        response.setUnmaterializedExposureCount(totalUnmaterializedExposureCount);
        response.setAffectedCount(counts.eventCount() + counts.exposureCount());
        response.setMaterializedCount(counts.materializedEventCount() + counts.materializedExposureCount());
        response.setUnmaterializedCount(totalUnmaterializedEventCount + totalUnmaterializedExposureCount);
        response.setGroups(counts.groups());
    }

    private void applyReplayPlanSegments(EventReplayPlanResponse response,
                                         ExperimentMetadata metadata,
                                         String experimentId,
                                         EventReplayScope replayScope,
                                         EventReplayPlanRequest request) {
        int requestedSegmentCount = normalizeReplaySegmentCount(request == null ? null : request.getSegmentCount());
        response.setRequestedSegmentCount(requestedSegmentCount);
        if (requestedSegmentCount <= 1) {
            response.setSegmentCount(0);
            response.setSegmentRecoverySupported(false);
            response.setSegmentRecoveryMessage("未请求分段巡检；如需分段恢复，请同时传入 startTime、endTime 和 segmentCount > 1");
            response.setMaxSegmentAffectedCount(0L);
            response.setMaxSegmentUnmaterializedCount(0L);
            response.setSegments(List.of());
            return;
        }
        if (replayScope.startTime() == null || replayScope.endTime() == null) {
            response.setSegmentCount(0);
            response.setSegmentRecoverySupported(false);
            response.setSegmentRecoveryMessage("分段巡检需要同时指定 startTime 和 endTime");
            response.setMaxSegmentAffectedCount(0L);
            response.setMaxSegmentUnmaterializedCount(0L);
            response.setSegments(List.of());
            return;
        }

        List<EventReplayPlanResponse.ReplayPlanSegment> segments =
                buildReplayPlanSegments(metadata, experimentId, replayScope, requestedSegmentCount);
        response.setSegments(segments);
        response.setSegmentCount(segments.size());
        response.setSegmentRecoverySupported(!segments.isEmpty());
        response.setSegmentRecoveryMessage(segments.isEmpty()
                ? "当前时间范围无法生成分段"
                : "可使用 /events/replay/materialization/repair/segments/{segmentIndex} 按分段修复缺账本");
        response.setMaxSegmentAffectedCount(segments.stream()
                .mapToLong(segment -> resolveLong(segment.getAffectedCount()))
                .max()
                .orElse(0L));
        response.setMaxSegmentUnmaterializedCount(segments.stream()
                .mapToLong(segment -> resolveLong(segment.getUnmaterializedCount()))
                .max()
                .orElse(0L));
    }

    private List<EventReplayPlanResponse.ReplayPlanSegment> buildReplayPlanSegments(
            ExperimentMetadata metadata, String experimentId, EventReplayScope replayScope, int segmentCount) {
        List<ReplaySegmentWindow> windows = buildReplaySegmentWindows(replayScope.startTime(),
                replayScope.endTime(), segmentCount);
        List<EventReplayPlanResponse.ReplayPlanSegment> segments = new ArrayList<>();
        for (ReplaySegmentWindow window : windows) {
            EventReplayScope segmentScope = new EventReplayScope(
                    window.startTime(),
                    window.endTime(),
                    replayScope.eventTypes(),
                    replayScope.includeEvents(),
                    replayScope.includeExposures(),
                    false,
                    REPLAY_MODE_FILTERED_DERIVED_COPY);
            ReplayPlanCounts counts = calculateReplayPlanCounts(metadata, experimentId, segmentScope);
            EventReplayPlanResponse.ReplayPlanSegment segment = new EventReplayPlanResponse.ReplayPlanSegment();
            segment.setSegmentIndex(window.segmentIndex());
            segment.setSegmentKey(String.format("segment-%03d", window.segmentIndex()));
            segment.setStartTime(window.startTime());
            segment.setEndTime(window.endTime());
            segment.setIncludeEvents(segmentScope.includeEvents());
            segment.setIncludeExposures(segmentScope.includeExposures());
            segment.setEventTypes(segmentScope.eventTypes());
            segment.setGroupCount((long) counts.groups().size());
            segment.setEventCount(counts.eventCount());
            segment.setMaterializedEventCount(counts.materializedEventCount());
            segment.setUnmaterializedEventCount(counts.unmaterializedEventCount());
            segment.setExposureCount(counts.exposureCount());
            segment.setMaterializedExposureCount(counts.materializedExposureCount());
            segment.setUnmaterializedExposureCount(counts.unmaterializedExposureCount());
            segment.setAffectedCount(counts.affectedCount());
            segment.setMaterializedCount(counts.materializedCount());
            segment.setUnmaterializedCount(counts.unmaterializedCount());
            segment.setRecommendedAction(counts.unmaterializedCount() > 0L
                    ? REPLAY_SEGMENT_ACTION_REPAIR_MATERIALIZATION
                    : REPLAY_SEGMENT_ACTION_NONE);
            segment.setMessage(counts.unmaterializedCount() > 0L
                    ? "该分段存在缺账本事实，可单独执行分段修复"
                    : "该分段账本覆盖完整，无需修复");
            segments.add(segment);
        }
        return segments;
    }

    private List<ReplaySegmentWindow> buildReplaySegmentWindows(LocalDateTime startTime,
                                                               LocalDateTime endTime,
                                                               int segmentCount) {
        if (startTime == null || endTime == null || endTime.isBefore(startTime)) {
            return List.of();
        }
        long totalNanos = Duration.between(startTime, endTime).toNanos();
        if (totalNanos <= 0L) {
            return List.of(new ReplaySegmentWindow(0, startTime, endTime));
        }
        List<ReplaySegmentWindow> windows = new ArrayList<>();
        LocalDateTime segmentStartTime = startTime;
        for (int index = 0; index < segmentCount && !segmentStartTime.isAfter(endTime); index++) {
            LocalDateTime segmentEndTime = index == segmentCount - 1
                    ? endTime
                    : startTime.plusNanos(resolveReplaySegmentEndOffset(totalNanos, segmentCount, index));
            if (segmentEndTime.isAfter(endTime)) {
                segmentEndTime = endTime;
            }
            if (segmentEndTime.isBefore(segmentStartTime)) {
                segmentEndTime = segmentStartTime;
            }
            windows.add(new ReplaySegmentWindow(index, segmentStartTime, segmentEndTime));
            segmentStartTime = segmentEndTime.plusNanos(1L);
        }
        return windows;
    }

    private long resolveReplaySegmentEndOffset(long totalNanos, int segmentCount, int segmentIndex) {
        long segmentOrdinal = segmentIndex + 1L;
        long baseStepNanos = totalNanos / segmentCount;
        long remainderNanos = totalNanos % segmentCount;
        return baseStepNanos * segmentOrdinal + Math.min(remainderNanos, segmentOrdinal);
    }

    private int normalizeReplaySegmentCount(Integer segmentCount) {
        if (segmentCount == null) {
            return 0;
        }
        if (segmentCount <= 1) {
            return Math.max(0, segmentCount);
        }
        return Math.min(segmentCount, MAX_REPLAY_PLAN_SEGMENT_COUNT);
    }

    @Override
    public EventPipelineOperationResponse repairEventMaterialization(String experimentId, EventReplayPlanRequest request,
                                                                     String operator) {
        EventReplayPlanResponse replayPlan = planEventReplay(experimentId, request);
        long unmaterializedCount = resolveLong(replayPlan.getUnmaterializedCount());
        LocalDateTime operatedAt = LocalDateTime.now();
        String normalizedOperator = StringUtils.hasText(operator) ? operator : "system";
        if (unmaterializedCount == 0L) {
            return buildEventPipelineOperationResponse(
                    experimentId,
                    EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION,
                    normalizedOperator,
                    operatedAt,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "当前重放计划没有缺失派生物化账本的事实，无需修复");
        }
        EventReplayScope replayScope = buildReplayScope(request);
        boolean planUnmaterializedOnly = !Boolean.TRUE.equals(replayPlan.getFullDerivedReplay());
        EventReplayJobRecord replayJob = startReplayJob(experimentId, normalizedOperator, operatedAt, replayScope,
                replayPlan, planUnmaterializedOnly);
        recordReplayJobSubmitted();
        if (Boolean.TRUE.equals(replayPlan.getFullDerivedReplay())) {
            return executeReplayJobAndBuildResponse(
                    replayJob,
                    EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION,
                    normalizedOperator,
                    operatedAt,
                    "已通过全量派生重放修复缺失派生物化账本事实: unmaterializedCount="
                            + unmaterializedCount);
        }
        return executeMaterializationRepairJobAndBuildResponse(
                replayJob,
                normalizedOperator,
                operatedAt,
                "已通过局部补物化修复缺失派生物化账本事实: unmaterializedCount="
                        + unmaterializedCount
                        + "；仅补齐缺账本事实，广义派生漂移仍需全量重放");
    }

    @Override
    public EventPipelineOperationResponse repairEventMaterializationSegment(String experimentId,
                                                                            EventReplayPlanRequest request,
                                                                            int segmentIndex,
                                                                            String operator) {
        EventReplayPlanResponse replayPlan = planEventReplay(experimentId, request);
        EventReplayPlanResponse.ReplayPlanSegment segment = resolveReplayPlanSegment(replayPlan, segmentIndex);
        LocalDateTime operatedAt = LocalDateTime.now();
        String normalizedOperator = StringUtils.hasText(operator) ? operator : "system";
        if (resolveLong(segment.getUnmaterializedCount()) == 0L) {
            EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                    experimentId,
                    EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION,
                    normalizedOperator,
                    operatedAt,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "当前重放分段没有缺失派生物化账本的事实，无需修复: segmentIndex=" + segmentIndex);
            applyReplayScope(response, buildSegmentReplayScope(replayPlan, segment));
            return response;
        }

        EventReplayPlanRequest segmentRequest = buildSegmentReplayRequest(replayPlan, segment);
        EventPipelineOperationResponse response = repairEventMaterialization(experimentId, segmentRequest,
                normalizedOperator);
        response.setMessage("已执行分段缺账本修复: segmentIndex="
                + segmentIndex
                + ", segmentKey="
                + segment.getSegmentKey()
                + "；"
                + response.getMessage());
        return response;
    }

    private EventReplayPlanResponse.ReplayPlanSegment resolveReplayPlanSegment(EventReplayPlanResponse replayPlan,
                                                                               int segmentIndex) {
        if (!Boolean.TRUE.equals(replayPlan.getSegmentRecoverySupported())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "当前重放计划不支持分段恢复，请同时传入 startTime、endTime 和 segmentCount > 1");
        }
        if (segmentIndex < 0 || replayPlan.getSegments() == null || segmentIndex >= replayPlan.getSegments().size()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "重放计划分段序号越界: segmentIndex="
                            + segmentIndex
                            + ", segmentCount="
                            + resolveInteger(replayPlan.getSegmentCount()));
        }
        return replayPlan.getSegments().get(segmentIndex);
    }

    private EventReplayPlanRequest buildSegmentReplayRequest(EventReplayPlanResponse replayPlan,
                                                             EventReplayPlanResponse.ReplayPlanSegment segment) {
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setStartTime(segment.getStartTime());
        request.setEndTime(segment.getEndTime());
        request.setEventTypes(replayPlan.getEventTypes());
        request.setIncludeEvents(replayPlan.getIncludeEvents());
        request.setIncludeExposures(replayPlan.getIncludeExposures());
        return request;
    }

    private long clampMaterializedCount(long matchedCount, long materializedCount) {
        return Math.min(Math.max(0L, materializedCount), Math.max(0L, matchedCount));
    }

    private void validateFilteredCopyReplayPlan(EventReplayScope replayScope, EventReplayPlanResponse replayPlan) {
        if (replayScope.fullDerivedReplay()) {
            return;
        }
        long maxFilteredCopyFacts = Math.max(0L, eventPipelineReplayMaxFilteredCopyFacts);
        if (maxFilteredCopyFacts == 0L) {
            return;
        }
        long affectedCount = resolveLong(replayPlan.getAffectedCount());
        if (affectedCount > maxFilteredCopyFacts) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "筛选范围复制型 replay 影响事实数超过上限: affectedCount="
                            + affectedCount
                            + ", maxFilteredCopyFacts="
                            + maxFilteredCopyFacts
                            + "；请缩小时间窗口/事件类型，或使用全量 replay 修复整体派生漂移");
        }
    }

    @Override
    public List<EventReplayJobResponse> listEventReplayJobs(String experimentId, int limit) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        int normalizedLimit = Math.min(MAX_REPLAY_JOB_QUERY_LIMIT, Math.max(1, limit));
        return eventReplayJobRepository.listRecentByExperimentId(experimentId, normalizedLimit).stream()
                .map(this::buildEventReplayJobResponse)
                .toList();
    }

    @Override
    public EventReplayJobResponse getEventReplayJob(String experimentId, String replayJobId) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        return buildEventReplayJobResponse(resolveEventReplayJobOrThrow(experimentId, replayJobId));
    }

    @Override
    public EventPipelineOperationResponse cancelEventReplayJob(String experimentId, String replayJobId,
                                                               String operator) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        EventReplayJobRecord replayJob = resolveEventReplayJobOrThrow(experimentId, replayJobId);
        if (EventReplayJobRecord.STATUS_CANCEL_REQUESTED.equals(replayJob.getJobStatus())) {
            EventPipelineOperationResponse response = buildCancelReplayJobResponse(replayJob, operator,
                    LocalDateTime.now(), "事件重放任务取消已在处理中");
            response.setReplayJobStatus(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
            return response;
        }
        if (!EventReplayJobRecord.STATUS_RUNNING.equals(replayJob.getJobStatus())) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "只有运行中的事件重放任务可以取消: jobStatus=" + replayJob.getJobStatus());
        }
        LocalDateTime operatedAt = LocalDateTime.now();
        String normalizedOperator = StringUtils.hasText(operator) ? operator : "system";
        boolean requested = eventReplayJobRepository.requestCancellation(replayJobId,
                REPLAY_JOB_CANCEL_REQUESTED_MESSAGE + ": operator=" + normalizedOperator);
        if (!requested) {
            throw new BusinessException(ResponseCode.CONFLICT, "事件重放任务状态已变化，请刷新后重试");
        }
        replayJob.setJobStatus(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
        replayJob.setErrorMessage(REPLAY_JOB_CANCEL_REQUESTED_MESSAGE + ": operator=" + normalizedOperator);

        return buildCancelReplayJobResponse(replayJob, normalizedOperator, operatedAt,
                "已受理事件重放任务取消请求，任务会在一致性安全点释放互斥键");
    }

    private EventPipelineOperationResponse buildCancelReplayJobResponse(EventReplayJobRecord replayJob,
                                                                        String operator,
                                                                        LocalDateTime operatedAt,
                                                                        String message) {
        EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                replayJob.getExperimentId(),
                EVENT_PIPELINE_OPERATION_CANCEL_REPLAY_JOB,
                operator,
                operatedAt,
                0L,
                resolveLong(replayJob.getEventCount()),
                resolveLong(replayJob.getExposureCount()),
                resolveLong(replayJob.getGroupCount()),
                resolveLong(replayJob.getMabRewardCount()),
                message);
        response.setReplayJobId(replayJob.getReplayJobId());
        response.setReplayJobStatus(replayJob.getJobStatus());
        applyReplayScope(response, replayJob);
        return response;
    }

    private void submitReplayJob(EventReplayJobRecord replayJob) {
        try {
            eventReplayTaskExecutor.execute(() -> {
                try {
                    executeReplayJobToTerminal(replayJob);
                } catch (RuntimeException exception) {
                    log.warn("事件重放任务后台执行失败: replayJobId={}, experimentId={}",
                            replayJob.getReplayJobId(), replayJob.getExperimentId(), exception);
                }
            });
            recordReplayJobSubmitted();
        } catch (RuntimeException exception) {
            markReplayJobFailed(replayJob.getReplayJobId(), exception);
            recordReplayJobSubmitRejected();
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "事件重放任务提交失败", exception);
        }
    }

    private EventPipelineOperationResponse executeReplayJobAndBuildResponse(EventReplayJobRecord replayJob,
                                                                            String operation,
                                                                            String operator,
                                                                            LocalDateTime operatedAt,
                                                                            String successMessage) {
        EventPipelineRebuildResult result = executeReplayJobToTerminal(replayJob);
        if (result == null) {
            EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                    replayJob.getExperimentId(),
                    operation,
                    operator,
                    operatedAt,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    EVENT_PIPELINE_OPERATION_CANCELLED,
                    REPLAY_JOB_CANCELLED_MESSAGE);
            response.setReplayJobId(replayJob.getReplayJobId());
            response.setReplayJobStatus(EventReplayJobRecord.STATUS_CANCELLED);
            applyReplayScope(response, replayJob);
            return response;
        }
        long affectedCount = result.getEventCount() + result.getExposureCount();
        EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                replayJob.getExperimentId(),
                operation,
                operator,
                operatedAt,
                affectedCount,
                result.getEventCount(),
                result.getExposureCount(),
                result.getGroupCount(),
                result.getMabRewardCount(),
                successMessage);
        response.setReplayJobId(replayJob.getReplayJobId());
        response.setReplayJobStatus(EventReplayJobRecord.STATUS_SUCCEEDED);
        applyReplayScope(response, replayJob);
        return response;
    }

    private EventPipelineOperationResponse executeMaterializationRepairJobAndBuildResponse(
            EventReplayJobRecord replayJob, String operator, LocalDateTime operatedAt, String successMessage) {
        EventPipelineRebuildResult result = executeMaterializationRepairJobToTerminal(replayJob);
        if (result == null) {
            EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                    replayJob.getExperimentId(),
                    EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION,
                    operator,
                    operatedAt,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    EVENT_PIPELINE_OPERATION_CANCELLED,
                    REPLAY_JOB_CANCELLED_MESSAGE);
            response.setReplayJobId(replayJob.getReplayJobId());
            response.setReplayJobStatus(EventReplayJobRecord.STATUS_CANCELLED);
            applyReplayScope(response, replayJob);
            return response;
        }
        long affectedCount = result.getEventCount() + result.getExposureCount();
        EventPipelineOperationResponse response = buildEventPipelineOperationResponse(
                replayJob.getExperimentId(),
                EVENT_PIPELINE_OPERATION_REPAIR_MATERIALIZATION,
                operator,
                operatedAt,
                affectedCount,
                result.getEventCount(),
                result.getExposureCount(),
                result.getGroupCount(),
                result.getMabRewardCount(),
                successMessage);
        response.setReplayJobId(replayJob.getReplayJobId());
        response.setReplayJobStatus(EventReplayJobRecord.STATUS_SUCCEEDED);
        applyReplayScope(response, replayJob);
        return response;
    }

    private EventPipelineRebuildResult executeReplayJobToTerminal(EventReplayJobRecord replayJob) {
        long startedNanos = System.nanoTime();
        if (isReplayJobCancellationRequested(replayJob)) {
            markReplayJobCancelled(replayJob.getReplayJobId(), REPLAY_JOB_CANCELLED_MESSAGE);
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_CANCELLED, startedNanos);
            return null;
        }

        EventPipelineRebuildResult result;
        boolean stopWhenCancellationRequested = !Boolean.TRUE.equals(replayJob.getFullDerivedReplay());
        EventReplayProgressReporter progressReporter = buildReplayProgressReporter(replayJob,
                stopWhenCancellationRequested);
        try {
            if (Boolean.TRUE.equals(replayJob.getFullDerivedReplay())) {
                result = eventInboxMaterializer.rebuildDerivedData(replayJob.getExperimentId(),
                        replayJob.getReplayJobId(), progressReporter);
            } else {
                result = eventInboxMaterializer.copyReplayDerivedData(
                        replayJob.getExperimentId(),
                        replayJob.getScopeStartTime(),
                        replayJob.getScopeEndTime(),
                        replayJob.getEventTypes(),
                        Boolean.TRUE.equals(replayJob.getIncludeEvents()),
                        Boolean.TRUE.equals(replayJob.getIncludeExposures()),
                        replayJob.getReplayJobId(),
                        progressReporter);
            }
        } catch (RuntimeException exception) {
            markReplayJobFailed(replayJob.getReplayJobId(), exception);
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_FAILED, startedNanos);
            throw exception;
        }

        long affectedCount = result.getEventCount() + result.getExposureCount();
        boolean succeeded = eventReplayJobRepository.markSucceeded(replayJob.getReplayJobId(), affectedCount,
                result.getEventCount(), result.getExposureCount(), result.getGroupCount(),
                result.getMabRewardCount(), LocalDateTime.now());
        if (succeeded) {
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_SUCCEEDED, startedNanos);
            return result;
        }
        if (isReplayJobCancellationRequested(replayJob)) {
            String cancellationMessage = Boolean.TRUE.equals(replayJob.getFullDerivedReplay())
                    ? REPLAY_JOB_CANCELLED_MESSAGE + "，派生数据已完成一致性重建"
                    : REPLAY_JOB_CANCELLED_MESSAGE + "，筛选范围事实已完成复制型补派生";
            markReplayJobCancelled(replayJob.getReplayJobId(), cancellationMessage);
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_CANCELLED, startedNanos);
            return null;
        }
        BusinessException exception = new BusinessException(ResponseCode.CONFLICT, "事件重放任务状态已变化，无法标记成功");
        markReplayJobFailed(replayJob.getReplayJobId(), exception);
        recordReplayJobTerminal(EventReplayJobRecord.STATUS_FAILED, startedNanos);
        throw exception;
    }

    private EventPipelineRebuildResult executeMaterializationRepairJobToTerminal(EventReplayJobRecord replayJob) {
        long startedNanos = System.nanoTime();
        if (isReplayJobCancellationRequested(replayJob)) {
            markReplayJobCancelled(replayJob.getReplayJobId(), REPLAY_JOB_CANCELLED_MESSAGE);
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_CANCELLED, startedNanos);
            return null;
        }

        EventPipelineRebuildResult result;
        EventReplayProgressReporter progressReporter = buildReplayProgressReporter(replayJob, true);
        try {
            result = eventInboxMaterializer.repairUnmaterializedDerivedData(
                    replayJob.getExperimentId(),
                    replayJob.getScopeStartTime(),
                    replayJob.getScopeEndTime(),
                    replayJob.getEventTypes(),
                    Boolean.TRUE.equals(replayJob.getIncludeEvents()),
                    Boolean.TRUE.equals(replayJob.getIncludeExposures()),
                    replayJob.getReplayJobId(),
                    progressReporter);
        } catch (RuntimeException exception) {
            markReplayJobFailed(replayJob.getReplayJobId(), exception);
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_FAILED, startedNanos);
            throw exception;
        }

        long affectedCount = result.getEventCount() + result.getExposureCount();
        boolean succeeded = eventReplayJobRepository.markSucceeded(replayJob.getReplayJobId(), affectedCount,
                result.getEventCount(), result.getExposureCount(), result.getGroupCount(),
                result.getMabRewardCount(), LocalDateTime.now());
        if (succeeded) {
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_SUCCEEDED, startedNanos);
            return result;
        }
        if (isReplayJobCancellationRequested(replayJob)) {
            markReplayJobCancelled(replayJob.getReplayJobId(),
                    REPLAY_JOB_CANCELLED_MESSAGE + "，缺账本事实已完成一致性补物化");
            recordReplayJobTerminal(EventReplayJobRecord.STATUS_CANCELLED, startedNanos);
            return null;
        }
        BusinessException exception = new BusinessException(ResponseCode.CONFLICT, "事件重放任务状态已变化，无法标记成功");
        markReplayJobFailed(replayJob.getReplayJobId(), exception);
        recordReplayJobTerminal(EventReplayJobRecord.STATUS_FAILED, startedNanos);
        throw exception;
    }

    private boolean isReplayJobCancellationRequested(EventReplayJobRecord replayJob) {
        EventReplayJobRecord latestJob = eventReplayJobRepository.findByExperimentIdAndReplayJobId(
                replayJob.getExperimentId(), replayJob.getReplayJobId());
        if (latestJob == null) {
            return false;
        }
        return EventReplayJobRecord.STATUS_CANCEL_REQUESTED.equals(latestJob.getJobStatus())
                || EventReplayJobRecord.STATUS_CANCELLED.equals(latestJob.getJobStatus());
    }

    private EventReplayProgressReporter buildReplayProgressReporter(EventReplayJobRecord replayJob,
                                                                    boolean stopWhenCancellationRequested) {
        return result -> {
            updateReplayJobProgress(replayJob.getReplayJobId(), result);
            return !stopWhenCancellationRequested || !isReplayJobCancellationRequested(replayJob);
        };
    }

    private void updateReplayJobProgress(String replayJobId, EventPipelineRebuildResult result) {
        if (result == null) {
            return;
        }
        try {
            eventReplayJobRepository.updateProgress(
                    replayJobId,
                    result.getEventCount() + result.getExposureCount(),
                    result.getEventCount(),
                    result.getExposureCount(),
                    result.getGroupCount(),
                    result.getMabRewardCount());
        } catch (RuntimeException exception) {
            log.warn("更新事件重放任务进度失败: replayJobId={}", replayJobId, exception);
        }
    }

    private void markReplayJobCancelled(String replayJobId, String message) {
        try {
            eventReplayJobRepository.markCancelled(replayJobId, message, LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("标记事件重放任务取消状态失败: replayJobId={}", replayJobId, exception);
        }
    }

    private List<String> normalizeReplayEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return Collections.emptyList();
        }
        return eventTypes.stream()
                .filter(StringUtils::hasText)
                .map(eventType -> eventType.trim().toUpperCase())
                .distinct()
                .toList();
    }

    private EventReplayJobRecord resolveEventReplayJobOrThrow(String experimentId, String replayJobId) {
        if (!StringUtils.hasText(replayJobId)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "事件重放任务ID不能为空");
        }
        EventReplayJobRecord replayJob = eventReplayJobRepository.findByExperimentIdAndReplayJobId(experimentId,
                replayJobId);
        if (replayJob == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "事件重放任务不存在");
        }
        return replayJob;
    }

    private EventReplayScope buildReplayScope(EventReplayPlanRequest request) {
        EventReplayPlanRequest normalizedRequest = request == null ? new EventReplayPlanRequest() : request;
        boolean includeEvents = normalizedRequest.getIncludeEvents() == null
                || Boolean.TRUE.equals(normalizedRequest.getIncludeEvents());
        boolean includeExposures = normalizedRequest.getIncludeExposures() == null
                || Boolean.TRUE.equals(normalizedRequest.getIncludeExposures());
        if (!includeEvents && !includeExposures) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "至少需要包含事件或曝光事实");
        }
        if (normalizedRequest.getStartTime() != null
                && normalizedRequest.getEndTime() != null
                && normalizedRequest.getEndTime().isBefore(normalizedRequest.getStartTime())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "重放计划结束时间不能早于开始时间");
        }
        List<String> eventTypes = normalizeReplayEventTypes(normalizedRequest.getEventTypes());
        if (!includeEvents && !eventTypes.isEmpty()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "不包含事件事实时不能指定事件类型筛选");
        }
        boolean fullDerivedReplay = includeEvents
                && includeExposures
                && normalizedRequest.getStartTime() == null
                && normalizedRequest.getEndTime() == null
                && eventTypes.isEmpty();
        String replayMode = fullDerivedReplay
                ? REPLAY_MODE_FULL_DERIVED_REBUILD
                : REPLAY_MODE_FILTERED_DERIVED_COPY;
        return new EventReplayScope(normalizedRequest.getStartTime(), normalizedRequest.getEndTime(), eventTypes,
                includeEvents, includeExposures, fullDerivedReplay, replayMode);
    }

    private EventReplayScope buildSegmentReplayScope(EventReplayPlanResponse replayPlan,
                                                     EventReplayPlanResponse.ReplayPlanSegment segment) {
        return new EventReplayScope(
                segment.getStartTime(),
                segment.getEndTime(),
                replayPlan.getEventTypes() == null ? List.of() : replayPlan.getEventTypes(),
                !Boolean.FALSE.equals(replayPlan.getIncludeEvents()),
                !Boolean.FALSE.equals(replayPlan.getIncludeExposures()),
                false,
                REPLAY_MODE_FILTERED_DERIVED_COPY);
    }

    private EventReplayJobRecord startReplayJob(String experimentId, String operator, LocalDateTime startedAt,
                                                EventReplayScope replayScope, EventReplayPlanResponse replayPlan,
                                                boolean planUnmaterializedOnly) {
        long timeoutMinutes = Math.max(1L, eventPipelineReplayJobTimeoutMinutes);
        LocalDateTime staleBefore = startedAt.minusMinutes(timeoutMinutes);
        eventReplayJobRepository.expireStaleRunningJobs(experimentId, staleBefore, startedAt,
                "重放任务超过 " + timeoutMinutes + " 分钟未完成，已被新任务接管");

        EventReplayJobRecord replayJob = new EventReplayJobRecord();
        replayJob.setReplayJobId(REPLAY_JOB_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        replayJob.setExperimentId(experimentId);
        replayJob.setOperator(operator);
        replayJob.setJobStatus(EventReplayJobRecord.STATUS_RUNNING);
        replayJob.setActiveKey(experimentId);
        replayJob.setReplayMode(replayScope.replayMode());
        replayJob.setScopeStartTime(replayScope.startTime());
        replayJob.setScopeEndTime(replayScope.endTime());
        replayJob.setEventTypes(replayScope.eventTypes());
        replayJob.setIncludeEvents(replayScope.includeEvents());
        replayJob.setIncludeExposures(replayScope.includeExposures());
        replayJob.setFullDerivedReplay(replayScope.fullDerivedReplay());
        applyReplayJobPlan(replayJob, replayPlan, planUnmaterializedOnly);
        replayJob.setAffectedCount(0L);
        replayJob.setEventCount(0L);
        replayJob.setExposureCount(0L);
        replayJob.setGroupCount(0L);
        replayJob.setMabRewardCount(0L);
        replayJob.setStartedAt(startedAt);

        if (!eventReplayJobRepository.createRunningJob(replayJob)) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "当前实验已有事件重放任务正在运行，请等待完成后再重试");
        }
        return replayJob;
    }

    private void applyReplayJobPlan(EventReplayJobRecord replayJob, EventReplayPlanResponse replayPlan,
                                    boolean planUnmaterializedOnly) {
        if (replayPlan == null) {
            replayJob.setPlannedAffectedCount(0L);
            replayJob.setPlannedEventCount(0L);
            replayJob.setPlannedExposureCount(0L);
            replayJob.setPlannedGroupCount(0L);
            return;
        }
        long plannedEventCount = planUnmaterializedOnly
                ? resolveLong(replayPlan.getUnmaterializedEventCount())
                : resolveLong(replayPlan.getEventCount());
        long plannedExposureCount = planUnmaterializedOnly
                ? resolveLong(replayPlan.getUnmaterializedExposureCount())
                : resolveLong(replayPlan.getExposureCount());
        replayJob.setPlannedEventCount(plannedEventCount);
        replayJob.setPlannedExposureCount(plannedExposureCount);
        replayJob.setPlannedAffectedCount(plannedEventCount + plannedExposureCount);
        replayJob.setPlannedGroupCount(resolvePlannedGroupCount(replayPlan, planUnmaterializedOnly));
    }

    private long resolvePlannedGroupCount(EventReplayPlanResponse replayPlan, boolean planUnmaterializedOnly) {
        if (replayPlan.getGroups() == null || replayPlan.getGroups().isEmpty()) {
            return resolveLong(replayPlan.getGroupCount());
        }
        return replayPlan.getGroups().stream()
                .filter(group -> shouldCountPlannedReplayGroup(replayPlan, group, planUnmaterializedOnly))
                .count();
    }

    private boolean shouldCountPlannedReplayGroup(EventReplayPlanResponse replayPlan,
                                                  EventReplayPlanResponse.GroupReplayPlan group,
                                                  boolean planUnmaterializedOnly) {
        if (!planUnmaterializedOnly && Boolean.TRUE.equals(replayPlan.getFullDerivedReplay())) {
            return true;
        }
        long plannedFacts = planUnmaterializedOnly
                ? resolveLong(group.getUnmaterializedCount())
                : resolveLong(group.getAffectedCount());
        return plannedFacts > 0L;
    }

    private void applyReplayScope(EventPipelineOperationResponse response, EventReplayJobRecord replayJob) {
        response.setReplayMode(replayJob.getReplayMode());
        response.setScopeStartTime(replayJob.getScopeStartTime());
        response.setScopeEndTime(replayJob.getScopeEndTime());
        response.setEventTypes(replayJob.getEventTypes());
        response.setIncludeEvents(replayJob.getIncludeEvents());
        response.setIncludeExposures(replayJob.getIncludeExposures());
        response.setFullDerivedReplay(replayJob.getFullDerivedReplay());
    }

    private void applyReplayScope(EventPipelineOperationResponse response, EventReplayScope replayScope) {
        response.setReplayMode(replayScope.replayMode());
        response.setScopeStartTime(replayScope.startTime());
        response.setScopeEndTime(replayScope.endTime());
        response.setEventTypes(replayScope.eventTypes());
        response.setIncludeEvents(replayScope.includeEvents());
        response.setIncludeExposures(replayScope.includeExposures());
        response.setFullDerivedReplay(replayScope.fullDerivedReplay());
    }

    private void markReplayJobFailed(String replayJobId, RuntimeException exception) {
        try {
            eventReplayJobRepository.markFailed(replayJobId, truncateErrorMessage(exception.getMessage()),
                    LocalDateTime.now());
        } catch (RuntimeException markException) {
            log.warn("标记事件重放任务失败状态失败: replayJobId={}", replayJobId, markException);
        }
    }

    private void recordReplayJobSubmitted() {
        if (eventReplayMetrics != null) {
            eventReplayMetrics.recordSubmitted();
        }
    }

    private void recordReplayJobSubmitRejected() {
        if (eventReplayMetrics != null) {
            eventReplayMetrics.recordSubmitRejected();
        }
    }

    private void recordReplayJobTerminal(String status, long startedNanos) {
        if (eventReplayMetrics != null) {
            eventReplayMetrics.recordTerminal(status, System.nanoTime() - startedNanos);
        }
    }

    private String truncateErrorMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "事件重放失败";
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }

    private EventReplayJobResponse buildEventReplayJobResponse(EventReplayJobRecord record) {
        EventReplayJobResponse response = new EventReplayJobResponse();
        response.setReplayJobId(record.getReplayJobId());
        response.setExperimentId(record.getExperimentId());
        response.setOperator(record.getOperator());
        response.setJobStatus(record.getJobStatus());
        response.setActiveKey(record.getActiveKey());
        response.setReplayMode(record.getReplayMode());
        response.setScopeStartTime(record.getScopeStartTime());
        response.setScopeEndTime(record.getScopeEndTime());
        response.setEventTypes(record.getEventTypes());
        response.setIncludeEvents(record.getIncludeEvents());
        response.setIncludeExposures(record.getIncludeExposures());
        response.setFullDerivedReplay(record.getFullDerivedReplay());
        response.setPlannedAffectedCount(resolveLong(record.getPlannedAffectedCount()));
        response.setPlannedEventCount(resolveLong(record.getPlannedEventCount()));
        response.setPlannedExposureCount(resolveLong(record.getPlannedExposureCount()));
        response.setPlannedGroupCount(resolveLong(record.getPlannedGroupCount()));
        response.setProgressPercent(calculateReplayProgressPercent(record));
        response.setAffectedCount(resolveLong(record.getAffectedCount()));
        response.setEventCount(resolveLong(record.getEventCount()));
        response.setExposureCount(resolveLong(record.getExposureCount()));
        response.setGroupCount(resolveLong(record.getGroupCount()));
        response.setMabRewardCount(resolveLong(record.getMabRewardCount()));
        response.setErrorMessage(record.getErrorMessage());
        response.setStartedAt(record.getStartedAt());
        response.setFinishedAt(record.getFinishedAt());
        return response;
    }

    private int calculateReplayProgressPercent(EventReplayJobRecord record) {
        long plannedAffectedCount = resolveLong(record.getPlannedAffectedCount());
        if (plannedAffectedCount <= 0L) {
            return 100;
        }
        long affectedCount = Math.max(0L, resolveLong(record.getAffectedCount()));
        long progressPercent = Math.round((affectedCount * 100.0d) / plannedAffectedCount);
        return (int) Math.min(100L, Math.max(0L, progressPercent));
    }

    private record EventReplayScope(LocalDateTime startTime, LocalDateTime endTime, List<String> eventTypes,
                                    boolean includeEvents, boolean includeExposures, boolean fullDerivedReplay,
                                    String replayMode) {
    }

    private record ReplayPlanCounts(long eventCount, long exposureCount, long materializedEventCount,
                                    long materializedExposureCount,
                                    List<EventReplayPlanResponse.GroupReplayPlan> groups) {

        private long unmaterializedEventCount() {
            return eventCount - materializedEventCount;
        }

        private long unmaterializedExposureCount() {
            return exposureCount - materializedExposureCount;
        }

        private long affectedCount() {
            return eventCount + exposureCount;
        }

        private long materializedCount() {
            return materializedEventCount + materializedExposureCount;
        }

        private long unmaterializedCount() {
            return unmaterializedEventCount() + unmaterializedExposureCount();
        }
    }

    private record ReplaySegmentWindow(int segmentIndex, LocalDateTime startTime, LocalDateTime endTime) {
    }

    private void waitBeforeNextDrainPoll() {
        long idleWaitMs = Math.max(1L, eventPipelineDrainIdleWaitMs);
        try {
            Thread.sleep(idleWaitMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ExperimentMetadata getAccessibleExperimentMetadata(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata != null) {
            ApiKeyContextHolder.assertCanAccess(metadata);
        }
        return metadata;
    }

    private ExperimentMetadata getAccessibleExperimentMetadataOrThrow(String experimentId) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        return metadata;
    }

    private Map<String, Long> buildEventInboxStatusCounts(String experimentId) {
        Map<String, Long> statusCounts = new HashMap<>();
        List<EventInboxStatusCountEntity> countEntities =
                eventInboxRepository.countByExperimentIdGroupByStatus(experimentId);
        if (countEntities == null) {
            return statusCounts;
        }
        for (EventInboxStatusCountEntity countEntity : countEntities) {
            if (countEntity == null || !StringUtils.hasText(countEntity.getStatus())) {
                continue;
            }
            statusCounts.put(countEntity.getStatus(),
                    countEntity.getEventCount() == null ? 0L : countEntity.getEventCount());
        }
        return statusCounts;
    }

    private long getStatusCount(Map<String, Long> statusCounts, String status) {
        return statusCounts.getOrDefault(status, 0L);
    }

    private long resolveLong(Long value) {
        return value == null ? 0L : value;
    }

    private int resolveInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private long resolveMaxPendingSeconds(String experimentId, LocalDateTime generatedAt, long unfinishedCount) {
        if (unfinishedCount <= 0) {
            return 0L;
        }
        LocalDateTime oldestAcceptedAt = eventInboxRepository.selectOldestUnfinishedAcceptedAt(experimentId);
        if (oldestAcceptedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(oldestAcceptedAt, generatedAt).getSeconds());
    }

    private boolean isPipelineHealthy(long retryCount, long deadCount, long rejectedCount, long maxPendingSeconds) {
        return retryCount == 0L
                && deadCount == 0L
                && rejectedCount == 0L
                && maxPendingSeconds <= MAX_HEALTHY_PENDING_SECONDS;
    }

    private String resolvePipelineStatus(long totalCount, long pendingCount, long processingCount, long retryCount,
                                         long deadCount, long rejectedCount) {
        if (totalCount == 0L) {
            return PIPELINE_STATUS_NO_DATA;
        }
        if (deadCount > 0L) {
            return PIPELINE_STATUS_DEAD;
        }
        if (retryCount > 0L) {
            return PIPELINE_STATUS_RETRY;
        }
        if (rejectedCount > 0L) {
            return PIPELINE_STATUS_REJECTED;
        }
        if (pendingCount + processingCount > 0L) {
            return PIPELINE_STATUS_PENDING;
        }
        return PIPELINE_STATUS_DONE;
    }

    private EventPipelineOperationResponse buildEventPipelineOperationResponse(
            String experimentId,
            String operation,
            String operator,
            LocalDateTime operatedAt,
            long affectedCount,
            long eventCount,
            long exposureCount,
            long groupCount,
            long mabRewardCount,
            String message) {
        return buildEventPipelineOperationResponse(
                experimentId,
                operation,
                operator,
                operatedAt,
                affectedCount,
                eventCount,
                exposureCount,
                groupCount,
                mabRewardCount,
                EVENT_PIPELINE_OPERATION_SUCCESS,
                message);
    }

    private EventPipelineOperationResponse buildEventPipelineOperationResponse(
            String experimentId,
            String operation,
            String operator,
            LocalDateTime operatedAt,
            long affectedCount,
            long eventCount,
            long exposureCount,
            long groupCount,
            long mabRewardCount,
            String status,
            String message) {
        EventPipelineOperationResponse response = new EventPipelineOperationResponse();
        response.setExperimentId(experimentId);
        response.setOperation(operation);
        response.setOperator(StringUtils.hasText(operator) ? operator : "system");
        response.setStatus(status);
        response.setAffectedCount(affectedCount);
        response.setEventCount(eventCount);
        response.setExposureCount(exposureCount);
        response.setGroupCount(groupCount);
        response.setMabRewardCount(mabRewardCount);
        response.setMessage(message);
        response.setOperatedAt(operatedAt);
        return response;
    }
    
    /**
     * 计算实验组统计数据
     */
    private Statistics.GroupStatistics calculateGroupStatistics(String experimentId, String groupId,
                                                                  com.pisces.common.model.ExperimentGroup group,
                                                                  String baselineGroupId,
                                                                  List<EventDefinition> eventDefinitions,
                                                                  List<MetricDefinition> metricDefinitions) {
        Statistics.GroupStatistics groupStats = new Statistics.GroupStatistics();
        groupStats.setGroupId(groupId);
        groupStats.setGroupName(group != null ? group.getName() : groupId);
        groupStats.setIsBaseline(groupId.equals(baselineGroupId));
        groupStats.setTrafficRatio(group != null ? group.getTrafficRatio() : null);
        
        // 计算访客数（从数据服务获取，基于实际事件数据统计）
        long visitorCount = dataService.getVisitorCount(experimentId, groupId);
        
        // 计算事件统计
        Map<String, Long> eventCounts = new HashMap<>();
        String viewType = Event.EVENT_TYPE_VIEW;
        String clickType = Event.EVENT_TYPE_CLICK;
        String convertType = Event.EVENT_TYPE_CONVERT;
        
        long viewCount = dataService.getEventCount(experimentId, groupId, viewType);
        long clickCount = dataService.getEventCount(experimentId, groupId, clickType);
        long convertCount = dataService.getEventCount(experimentId, groupId, convertType);
        
        eventCounts.put(viewType, viewCount);
        eventCounts.put(clickType, clickCount);
        eventCounts.put(convertType, convertCount);
        if (eventDefinitions != null) {
            for (EventDefinition eventDefinition : eventDefinitions) {
                if (eventDefinition == null || !StringUtils.hasText(eventDefinition.getKey())) {
                    continue;
                }
                eventCounts.computeIfAbsent(eventDefinition.getKey(),
                        key -> dataService.getEventCount(experimentId, groupId, key));
            }
        }
        
        groupStats.setEventCounts(eventCounts);
        groupStats.setViewCount(viewCount);
        groupStats.setClickCount(clickCount);
        groupStats.setConversionCount(convertCount);

        Map<String, Double> metricValues = buildMetricValues(experimentId, groupId, viewCount, clickCount,
                convertCount, visitorCount, metricDefinitions);
        groupStats.setMetricValues(metricValues);

        double clickRate = metricValues.getOrDefault("click_rate", viewCount > 0 ? (double) clickCount / viewCount : 0.0);
        double conversionRate = metricValues.getOrDefault("conversion_rate",
                viewCount > 0 ? (double) convertCount / viewCount : 0.0);
        groupStats.setClickRate(clickRate);
        groupStats.setConversionRate(conversionRate);
        
        // 注意：Statistics.GroupStatistics中的userCount字段实际存储的是visitorCount
        groupStats.setUserCount(visitorCount);
        
        return groupStats;
    }

    private List<MetricDefinition> resolveMetricDefinitions(ExperimentMetadata metadata) {
        if (metadata.getMetricDefinitions() != null && !metadata.getMetricDefinitions().isEmpty()) {
            return metadata.getMetricDefinitions();
        }

        MetricDefinition clickRateMetric = new MetricDefinition();
        clickRateMetric.setKey("click_rate");
        clickRateMetric.setName("点击率");
        clickRateMetric.setAggregationType(MetricDefinition.AggregationType.RATE);
        clickRateMetric.setNumeratorEventType("CLICK");
        clickRateMetric.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        clickRateMetric.setDenominatorEventType("VIEW");

        MetricDefinition conversionRateMetric = new MetricDefinition();
        conversionRateMetric.setKey("conversion_rate");
        conversionRateMetric.setName("转化率");
        conversionRateMetric.setAggregationType(MetricDefinition.AggregationType.RATE);
        conversionRateMetric.setNumeratorEventType("CONVERT");
        conversionRateMetric.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        conversionRateMetric.setDenominatorEventType("VIEW");

        return List.of(clickRateMetric, conversionRateMetric);
    }

    private MetricDefinition resolvePrimaryMetric(List<MetricDefinition> metricDefinitions) {
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())) {
                return metricDefinition;
            }
        }
        return metricDefinitions.isEmpty() ? null : metricDefinitions.get(0);
    }

    private String resolveBaselineGroupId(ExperimentMetadata metadata) {
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            return null;
        }
        if (metadata.getGroups().containsKey("control")) {
            return "control";
        }
        if (metadata.getTraffic() != null && metadata.getTraffic().getAllocation() != null) {
            for (com.pisces.common.model.TrafficConfig.GroupAllocation allocation : metadata.getTraffic().getAllocation()) {
                if (allocation != null && StringUtils.hasText(allocation.getGroup())
                        && metadata.getGroups().containsKey(allocation.getGroup())) {
                    return allocation.getGroup();
                }
            }
        }
        return metadata.getGroups().keySet().stream().sorted().findFirst().orElse(null);
    }

    private MetricDefinition resolveRateMetricForInference(MetricDefinition primaryMetricDefinition) {
        if (primaryMetricDefinition != null
                && primaryMetricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE) {
            return primaryMetricDefinition;
        }
        MetricDefinition conversionRateMetric = new MetricDefinition();
        conversionRateMetric.setKey("conversion_rate");
        conversionRateMetric.setAggregationType(MetricDefinition.AggregationType.RATE);
        conversionRateMetric.setNumeratorEventType("CONVERT");
        conversionRateMetric.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        conversionRateMetric.setDenominatorEventType("VIEW");
        return conversionRateMetric;
    }

    private double extractPrimaryMetricValue(Statistics.GroupStatistics groupStatistics,
                                             MetricDefinition primaryMetricDefinition) {
        if (groupStatistics == null || primaryMetricDefinition == null || groupStatistics.getMetricValues() == null) {
            return groupStatistics != null && groupStatistics.getConversionRate() != null
                    ? groupStatistics.getConversionRate() : Double.NEGATIVE_INFINITY;
        }
        Double metricValue = groupStatistics.getMetricValues().get(primaryMetricDefinition.getKey());
        return metricValue != null ? metricValue : Double.NEGATIVE_INFINITY;
    }

    private Map<String, Double> buildMetricValues(String experimentId, String groupId, long viewCount,
                                                  long clickCount, long convertCount, long visitorCount,
                                                  List<MetricDefinition> metricDefinitions) {
        Map<String, Double> metricValues = new LinkedHashMap<>();
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (metricDefinition == null || metricDefinition.getKey() == null) {
                continue;
            }

            long numerator = resolveMetricNumerator(metricDefinition, viewCount, clickCount, convertCount,
                    experimentId, groupId);
            double metricValue = metricDefinition.getAggregationType() == MetricDefinition.AggregationType.COUNT
                    ? numerator
                    : calculateRateMetric(metricDefinition, numerator, experimentId, groupId, viewCount,
                    clickCount, convertCount, visitorCount);
            metricValues.put(metricDefinition.getKey(), metricValue);
        }
        return metricValues;
    }

    private long resolveMetricNumerator(MetricDefinition metricDefinition, long viewCount, long clickCount,
                                        long convertCount, String experimentId, String groupId) {
        String numeratorEventType = metricDefinition.getNumeratorEventType();
        if ("VIEW".equalsIgnoreCase(numeratorEventType)) {
            return viewCount;
        }
        if ("CLICK".equalsIgnoreCase(numeratorEventType)) {
            return clickCount;
        }
        if ("CONVERT".equalsIgnoreCase(numeratorEventType)) {
            return convertCount;
        }
        if (numeratorEventType == null || numeratorEventType.isBlank()) {
            return 0;
        }
        return dataService.getEventCount(experimentId, groupId, numeratorEventType.toUpperCase());
    }

    private long resolveMetricNumerator(MetricDefinition metricDefinition, Statistics.GroupStatistics groupStatistics,
                                        String experimentId, String groupId) {
        if (groupStatistics == null) {
            return 0L;
        }
        return resolveMetricNumerator(metricDefinition,
                groupStatistics.getViewCount() != null ? groupStatistics.getViewCount() : 0L,
                groupStatistics.getClickCount() != null ? groupStatistics.getClickCount() : 0L,
                groupStatistics.getConversionCount() != null ? groupStatistics.getConversionCount() : 0L,
                experimentId, groupId);
    }

    private double calculateRateMetric(MetricDefinition metricDefinition, long numerator, String experimentId,
                                       String groupId, long viewCount, long clickCount, long convertCount,
                                       long visitorCount) {
        long denominator = resolveMetricDenominator(metricDefinition, experimentId, groupId, viewCount, clickCount,
                convertCount, visitorCount);
        return denominator > 0 ? (double) numerator / denominator : 0.0;
    }

    private long resolveMetricDenominator(MetricDefinition metricDefinition, Statistics.GroupStatistics groupStatistics,
                                          String experimentId, String groupId) {
        if (groupStatistics == null) {
            return 0L;
        }
        return resolveMetricDenominator(metricDefinition, experimentId, groupId,
                groupStatistics.getViewCount() != null ? groupStatistics.getViewCount() : 0L,
                groupStatistics.getClickCount() != null ? groupStatistics.getClickCount() : 0L,
                groupStatistics.getConversionCount() != null ? groupStatistics.getConversionCount() : 0L,
                groupStatistics.getUserCount() != null ? groupStatistics.getUserCount() : 0L);
    }

    private long resolveMetricDenominator(MetricDefinition metricDefinition, String experimentId, String groupId,
                                          long viewCount, long clickCount, long convertCount, long visitorCount) {
        return switch (metricDefinition.getDenominatorType()) {
            case VISITOR_COUNT -> visitorCount;
            case ASSIGNMENT_COUNT -> dataService.getAssignmentCount(experimentId, groupId);
            case EXPOSURE_COUNT -> dataService.getExposureCount(experimentId, groupId);
            case EVENT_COUNT -> resolveEventDenominator(metricDefinition.getDenominatorEventType(), experimentId,
                    groupId, viewCount, clickCount, convertCount);
        };
    }

    private long resolveEventDenominator(String denominatorEventType, String experimentId, String groupId,
                                         long viewCount, long clickCount, long convertCount) {
        if ("VIEW".equalsIgnoreCase(denominatorEventType)) {
            return viewCount;
        }
        if ("CLICK".equalsIgnoreCase(denominatorEventType)) {
            return clickCount;
        }
        if ("CONVERT".equalsIgnoreCase(denominatorEventType)) {
            return convertCount;
        }
        if (denominatorEventType == null || denominatorEventType.isBlank()) {
            return 0;
        }
        return dataService.getEventCount(experimentId, groupId, denominatorEventType.toUpperCase());
    }

    private List<String> resolveBreachedGuardrails(Map<String, Statistics.GroupStatistics> groupStatsMap,
                                                   List<MetricDefinition> metricDefinitions,
                                                   String baselineGroupId,
                                                   String targetGroupId) {
        if (baselineGroupId == null || targetGroupId == null || baselineGroupId.equals(targetGroupId)) {
            return new ArrayList<>();
        }

        Statistics.GroupStatistics baselineStats = groupStatsMap.get(baselineGroupId);
        Statistics.GroupStatistics targetStats = groupStatsMap.get(targetGroupId);
        if (baselineStats == null || targetStats == null
                || baselineStats.getMetricValues() == null || targetStats.getMetricValues() == null) {
            return new ArrayList<>();
        }

        List<String> breachedGuardrails = new ArrayList<>();
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (!Boolean.TRUE.equals(metricDefinition.getGuardrailMetric())) {
                continue;
            }
            Double baselineValue = baselineStats.getMetricValues().get(metricDefinition.getKey());
            Double targetValue = targetStats.getMetricValues().get(metricDefinition.getKey());
            if (baselineValue == null || targetValue == null) {
                continue;
            }
            if (targetValue < baselineValue) {
                breachedGuardrails.add(String.format("护栏指标 %s 下降（基准 %.4f -> 当前 %.4f）",
                        metricDefinition.getKey(), baselineValue, targetValue));
            }
        }
        return breachedGuardrails;
    }

    private Statistics.DataQualityCheck buildDataQualityCheck(String experimentId, ExperimentMetadata metadata,
                                                              Map<String, Statistics.GroupStatistics> groupStatsMap,
                                                              String baselineGroupId) {
        Statistics.DataQualityCheck dataQualityCheck = new Statistics.DataQualityCheck();
        List<String> blockingIssues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> srmResult = buildSrmResult(experimentId, metadata);
        boolean hasSrm = Boolean.TRUE.equals(srmResult.get("hasSRM"));
        dataQualityCheck.setHasSrm(hasSrm);
        if (srmResult.get("pValue") instanceof Number pValueNumber) {
            dataQualityCheck.setSrmPValue(pValueNumber.doubleValue());
        }
        if (hasSrm) {
            blockingIssues.add("检测到 SRM，当前实验分流比例异常");
        }

        long minAssignmentCount = Long.MAX_VALUE;
        long totalExposureCount = 0L;
        if (metadata.getGroups() != null) {
            for (String groupId : metadata.getGroups().keySet()) {
                long assignmentCount = dataService.getAssignmentCount(experimentId, groupId);
                minAssignmentCount = Math.min(minAssignmentCount, assignmentCount);
                totalExposureCount += dataService.getExposureCount(experimentId, groupId);
            }
        }
        if (minAssignmentCount == Long.MAX_VALUE) {
            minAssignmentCount = 0L;
        }
        if (minAssignmentCount <= 0) {
            blockingIssues.add("至少一个实验组尚无真实 assignment 数据");
        }
        if (totalExposureCount <= 0) {
            warnings.add("当前尚无 exposure 数据，曝光口径指标暂不可用于结论判断");
        }

        Statistics.GroupStatistics baselineStats = groupStatsMap.get(baselineGroupId);
        Double baselineRate = baselineStats != null ? baselineStats.getConversionRate() : null;
        if (baselineRate == null || baselineRate <= 0 || baselineRate >= 1) {
            warnings.add("基准组转化率不足以估算建议样本量，请先积累真实曝光与转化数据");
            dataQualityCheck.setSampleSizeReached(false);
        } else {
            long requiredSampleSize = StatisticalUtils.calculateSampleSize(baselineRate, DEFAULT_GATE_MDE,
                    DEFAULT_GATE_ALPHA, DEFAULT_GATE_POWER);
            dataQualityCheck.setRequiredSampleSizePerGroup(requiredSampleSize);
            boolean sampleSizeReached = minAssignmentCount >= requiredSampleSize;
            dataQualityCheck.setSampleSizeReached(sampleSizeReached);
            if (!sampleSizeReached) {
                blockingIssues.add(String.format("样本量不足，当前每组最少 assignment=%d，建议至少达到 %d",
                        minAssignmentCount, requiredSampleSize));
            }
        }

        dataQualityCheck.setAnalysisReady(blockingIssues.isEmpty());
        dataQualityCheck.setBlockingIssues(blockingIssues);
        dataQualityCheck.setWarnings(warnings);
        return dataQualityCheck;
    }

    private Map<String, Object> buildSrmResult(String experimentId, ExperimentMetadata metadata) {
        List<String> groupIds = new ArrayList<>(metadata.getGroups().keySet());
        long[] observed = new long[groupIds.size()];
        double[] expectedRatios = new double[groupIds.size()];

        boolean hasAssignmentFacts = false;
        for (int i = 0; i < groupIds.size(); i++) {
            String groupId = groupIds.get(i);
            long assignmentCount = dataService.getAssignmentCount(experimentId, groupId);
            observed[i] = assignmentCount;
            if (assignmentCount > 0) {
                hasAssignmentFacts = true;
            }
            com.pisces.common.model.ExperimentGroup group = metadata.getGroups().get(groupId);
            expectedRatios[i] = group != null && group.getTrafficRatio() != null
                    ? group.getTrafficRatio() : 1.0 / groupIds.size();
        }

        if (!hasAssignmentFacts) {
            for (int i = 0; i < groupIds.size(); i++) {
                observed[i] = dataService.getVisitorCount(experimentId, groupIds.get(i));
            }
        }
        return StatisticalUtils.detectSRM(observed, expectedRatios);
    }
    
    /**
     * 对比实验组
     */
    @Override
    public Map<String, Object> compareGroups(String experimentId) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        Statistics statistics = getStatistics(experimentId);
        if (statistics == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "实验不存在或没有统计数据");
            return error;
        }
        
        Map<String, Object> comparison = new HashMap<>();
        Map<String, Statistics.GroupStatistics> groupStats = statistics.getGroupStatistics();
        
        if (groupStats == null || groupStats.isEmpty()) {
            comparison.put("error", "实验组统计数据为空");
            return comparison;
        }
        
        if (groupStats.size() < 2) {
            comparison.put("message", "至少需要2个实验组才能对比");
            return comparison;
        }
        
        // 获取第一个组作为基准
        String baselineGroup = resolveBaselineGroupId(metadata);
        if (!StringUtils.hasText(baselineGroup) || !groupStats.containsKey(baselineGroup)) {
            baselineGroup = groupStats.keySet().iterator().next();
        }
        Statistics.GroupStatistics baseline = groupStats.get(baselineGroup);
        
        if (baseline == null) {
            comparison.put("error", "基准组统计数据为空");
            return comparison;
        }

        MetricDefinition primaryMetricDefinition = metadata != null
                ? resolvePrimaryMetric(resolveMetricDefinitions(metadata)) : null;
        
        comparison.put("baseline", baselineGroup);
        comparison.put("baselineStats", baseline);
        comparison.put("dataQualityCheck", statistics.getDataQualityCheck());
        
        // 对比其他组
        Map<String, Map<String, Object>> comparisons = new HashMap<>();
        for (Map.Entry<String, Statistics.GroupStatistics> entry : groupStats.entrySet()) {
            if (!entry.getKey().equals(baselineGroup)) {
                Statistics.GroupStatistics target = entry.getValue();
                if (target != null) {
                    Map<String, Object> comp = compareWithBaseline(baseline, target, primaryMetricDefinition);
                    comparisons.put(entry.getKey(), comp);
                }
            }
        }
        
        comparison.put("comparisons", comparisons);
        return comparison;
    }
    
    /**
     * 与基准组对比
     */
    private Map<String, Object> compareWithBaseline(Statistics.GroupStatistics baseline,
                                                    Statistics.GroupStatistics target,
                                                    MetricDefinition primaryMetricDefinition) {
        Map<String, Object> comparison = new HashMap<>();
        
        // 转化率对比
        double baselineRate = resolveComparisonMetricValue(baseline, primaryMetricDefinition);
        double targetRate = resolveComparisonMetricValue(target, primaryMetricDefinition);
        double rateDiff = targetRate - baselineRate;
        double rateChangePercent = baselineRate > 0 ? (rateDiff / baselineRate) * 100 : 0;
        
        comparison.put("conversionRate", targetRate);
        comparison.put("conversionRateChange", rateDiff);
        comparison.put("conversionRateChangePercent", rateChangePercent);
        
        // 事件数对比
        Map<String, Long> baselineEvents = baseline.getEventCounts();
        Map<String, Long> targetEvents = target.getEventCounts();
        
        if (baselineEvents == null) {
            baselineEvents = new HashMap<>();
        }
        if (targetEvents == null) {
            targetEvents = new HashMap<>();
        }
        
        Map<String, Map<String, Object>> eventComparison = new HashMap<>();
        Set<String> eventTypes = new java.util.HashSet<>(baselineEvents.keySet());
        eventTypes.addAll(targetEvents.keySet());
        
        for (String eventType : eventTypes) {
            long baselineCount = baselineEvents.getOrDefault(eventType, 0L);
            long targetCount = targetEvents.getOrDefault(eventType, 0L);
            long diff = targetCount - baselineCount;
            double changePercent = baselineCount > 0 ? ((double) diff / baselineCount) * 100 : 0;
            
            Map<String, Object> eventComp = new HashMap<>();
            eventComp.put("baseline", baselineCount);
            eventComp.put("target", targetCount);
            eventComp.put("difference", diff);
            eventComp.put("changePercent", changePercent);
            
            eventComparison.put(eventType, eventComp);
        }
        
        comparison.put("events", eventComparison);
        
        return comparison;
    }

    private double resolveComparisonMetricValue(Statistics.GroupStatistics groupStatistics,
                                                MetricDefinition primaryMetricDefinition) {
        if (groupStatistics == null) {
            return 0.0D;
        }
        if (primaryMetricDefinition != null
                && StringUtils.hasText(primaryMetricDefinition.getKey())
                && groupStatistics.getMetricValues() != null) {
            Double primaryMetricValue = groupStatistics.getMetricValues().get(primaryMetricDefinition.getKey());
            if (primaryMetricValue != null) {
                return primaryMetricValue;
            }
        }
        return groupStatistics.getConversionRate() != null ? groupStatistics.getConversionRate() : 0.0D;
    }
    
    @Override
    public Map<String, Object> statisticalSignificanceTest(String experimentId, String variantGroupId,
                                                            String baselineGroupId, Double confidenceLevel) {
        double confidence = confidenceLevel != null ? confidenceLevel : 0.95;
        Statistics statistics = getStatistics(experimentId);
        if (statistics == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "实验不存在或没有统计数据");
            return error;
        }
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        MetricDefinition primaryMetricDefinition = metadata != null
                ? resolvePrimaryMetric(resolveMetricDefinitions(metadata)) : null;
        Statistics.GroupStatistics variantGroupStats = statistics.getGroupStatistics().get(variantGroupId);
        Statistics.GroupStatistics baselineGroupStats = statistics.getGroupStatistics().get(baselineGroupId);
        MetricDefinition significanceMetricDefinition = resolveRateMetricForInference(primaryMetricDefinition);
        
        // 获取两组数据
        long variantConverts = resolveMetricNumerator(significanceMetricDefinition, variantGroupStats,
                experimentId, variantGroupId);
        long baselineConverts = resolveMetricNumerator(significanceMetricDefinition, baselineGroupStats,
                experimentId, baselineGroupId);
        long variantViews = resolveMetricDenominator(significanceMetricDefinition, variantGroupStats,
                experimentId, variantGroupId);
        long baselineViews = resolveMetricDenominator(significanceMetricDefinition, baselineGroupStats,
                experimentId, baselineGroupId);
        
        // 计算转化率
        double variantRate = variantViews > 0 ? (double) variantConverts / variantViews : 0.0;
        double baselineRate = baselineViews > 0 ? (double) baselineConverts / baselineViews : 0.0;
        
        // 计算提升率（Lift）
        double lift = baselineRate > 0 ? (variantRate - baselineRate) / baselineRate : 0.0;
        double absoluteDiff = variantRate - baselineRate;
        
        // 计算合并转化率（用于Z检验）
        double pooledRate = (variantConverts + baselineConverts) / (double) (variantViews + baselineViews);
        
        // 计算标准误差（使用合并方差估计）
        double se = 0.0;
        if (variantViews > 0 && baselineViews > 0) {
            se = Math.sqrt(pooledRate * (1 - pooledRate) * (1.0 / variantViews + 1.0 / baselineViews));
        }
        
        // 计算Z统计量
        double zStat = se > 0 ? absoluteDiff / se : 0.0;
        
        // 计算p值（双尾检验）
        double pValue = StatisticalUtils.zToPValue(zStat);

        // 获取Z临界值（精确计算，替代查表近似）
        double alpha = 1.0 - confidence;
        double zCritical = StatisticalUtils.normalQuantile(1.0 - alpha / 2.0);

        // 计算置信区间（使用非混合 SE，与学术标准一致）
        double ciSE = variantViews > 0 && baselineViews > 0
                ? Math.sqrt(variantRate * (1 - variantRate) / variantViews
                        + baselineRate * (1 - baselineRate) / baselineViews)
                : se;
        double marginOfError = zCritical * ciSE;
        double ciLower = absoluteDiff - marginOfError;
        double ciUpper = absoluteDiff + marginOfError;
        
        // 判断是否显著
        boolean isSignificant = pValue < (1 - confidence);
        
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        result.put("variantGroupId", variantGroupId);
        result.put("baselineGroupId", baselineGroupId);
        result.put("primaryMetricKey", primaryMetricDefinition != null ? primaryMetricDefinition.getKey() : null);
        result.put("metricKeyUsed", significanceMetricDefinition.getKey());
        if (primaryMetricDefinition != null
                && !significanceMetricDefinition.getKey().equals(primaryMetricDefinition.getKey())) {
            result.put("metricAlignmentWarning",
                    "当前显著性检验只支持比例型主指标，已回退到 conversion_rate 口径");
        }
        
        // 样本数据
        Map<String, Object> variantData = new HashMap<>();
        variantData.put("views", variantViews);
        variantData.put("conversions", variantConverts);
        variantData.put("conversionRate", variantRate);
        variantData.put("denominatorCount", variantViews);
        variantData.put("numeratorCount", variantConverts);
        result.put("variantData", variantData);
        
        Map<String, Object> baselineData = new HashMap<>();
        baselineData.put("views", baselineViews);
        baselineData.put("conversions", baselineConverts);
        baselineData.put("conversionRate", baselineRate);
        baselineData.put("denominatorCount", baselineViews);
        baselineData.put("numeratorCount", baselineConverts);
        result.put("baselineData", baselineData);
        
        // 效果指标
        result.put("absoluteDifference", absoluteDiff);
        result.put("relativeLift", lift);
        result.put("relativeLiftPercent", lift * 100);
        
        // 统计检验结果
        result.put("zStatistic", zStat);
        result.put("pValue", pValue);
        result.put("confidenceLevel", confidence);
        result.put("confidenceInterval", Map.of("lower", ciLower, "upper", ciUpper));
        result.put("marginOfError", marginOfError);
        result.put("isStatisticallySignificant", isSignificant);
        attachDataQualityCheck(result, statistics);
        
        // 结论
        String conclusion;
        if (isSignificant) {
            if (lift > 0) {
                conclusion = String.format("变体组相较于基准组有%.2f%%的显著提升（p=%.4f < %.2f）", 
                        lift * 100, pValue, 1 - confidence);
            } else {
                conclusion = String.format("变体组相较于基准组有%.2f%%的显著下降（p=%.4f < %.2f）", 
                        Math.abs(lift * 100), pValue, 1 - confidence);
            }
        } else {
            conclusion = String.format("变体组与基准组之间的差异不显著（p=%.4f >= %.2f），建议继续收集数据", 
                    pValue, 1 - confidence);
        }
        result.put("conclusion", applyQualityGateToConclusion(conclusion, statistics));
        
        return result;
    }
    
    @Override
    public Map<String, Object> calculateSampleSize(Double baselineRate, Double minimumDetectableEffect,
                                                   Double power, Double significance) {
        double p1 = baselineRate != null ? baselineRate : 0.10; // 默认基准转化率10%
        double mde = minimumDetectableEffect != null ? minimumDetectableEffect : 0.10; // 默认最小可检测效应10%
        double powerLevel = power != null ? power : DEFAULT_GATE_POWER; // 默认功效80%
        double alpha = significance != null ? significance : DEFAULT_GATE_ALPHA; // 默认显著性水平5%
        
        double p2 = p1 * (1 + mde); // 期望转化率 = 基准转化率 × (1 + MDE)

        // 使用 StatisticalUtils 精确计算样本量
        long sampleSizePerGroup = StatisticalUtils.calculateSampleSize(p1, mde, alpha, powerLevel);
        long totalSampleSize = sampleSizePerGroup * 2;
        
        Map<String, Object> result = new HashMap<>();
        result.put("baselineConversionRate", p1);
        result.put("expectedConversionRate", p2);
        result.put("minimumDetectableEffect", mde);
        result.put("minimumDetectableEffectPercent", mde * 100);
        result.put("power", powerLevel);
        result.put("significance", alpha);
        result.put("sampleSizePerGroup", sampleSizePerGroup);
        result.put("totalSampleSize", totalSampleSize);
        
        String recommendation = String.format(
                "为了检测%.1f%%的转化率提升（从%.2f%%到%.2f%%），" +
                "在%.0f%%显著性水平和%.0f%%功效下，每组需要至少%d个样本，总共需要%d个样本。",
                mde * 100, p1 * 100, p2 * 100, alpha * 100, powerLevel * 100, 
                sampleSizePerGroup, totalSampleSize);
        result.put("recommendation", recommendation);
        
        return result;
    }
    
    @Override
    public Map<String, Object> getBayesianAnalysis(String experimentId) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        return bayesianAnalysisService.getBayesianAnalysis(experimentId);
    }
    
    @Override
    public Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                              String baselineGroupId, Double winRateThreshold) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        double threshold = winRateThreshold != null ? winRateThreshold : 0.95;
        Map<String, Object> result = bayesianAnalysisService.shouldEarlyStop(experimentId, variantGroupId,
                baselineGroupId, threshold);
        Statistics statistics = getStatistics(experimentId);
        attachDataQualityCheck(result, statistics);
        if (!isAnalysisReady(statistics)) {
            result.put("canStop", false);
            result.put("shouldStop", false);
            result.put("decisionOverriddenByQualityGate", true);
            result.put("recommendation", buildQualityGateRecommendation(statistics));
        }
        return result;
    }
    
    @Override
    public Map<String, Object> causalInference(String experimentId, String treatmentGroupId,
                                              String controlGroupId, String method,
                                              Map<String, Object> params) {
        Statistics statistics = getStatistics(experimentId);
        Map<String, Object> gateResult = buildAnalysisGateResult("CAUSAL_INFERENCE", method, statistics);
        if (gateResult != null) {
            return gateResult;
        }

        Map<String, Object> contractResult = validateCausalInputContract(method, params);
        if (contractResult != null) {
            return contractResult;
        }

        Map<String, Object> result;
        String normalizedMethod = normalizeMethod(method);
        if (normalizedMethod == null) {
            return buildBlockedAnalysisResult("CAUSAL_INFERENCE", null,
                    "因果推断方法不能为空",
                    Collections.singletonList("method 不能为空"),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    null);
        }
        switch (normalizedMethod) {
            case "DID":
                String beforeStart = (String) params.get("beforePeriodStart");
                String beforeEnd = (String) params.get("beforePeriodEnd");
                String afterStart = (String) params.get("afterPeriodStart");
                String afterEnd = (String) params.get("afterPeriodEnd");
                result = causalInferenceService.analyzeByDID(experimentId, treatmentGroupId, controlGroupId,
                        beforeStart, beforeEnd, afterStart, afterEnd);
                break;
            case "PSM":
                @SuppressWarnings("unchecked")
                java.util.List<String> features = (java.util.List<String>) params.get("userFeatures");
                result = causalInferenceService.analyzeByPSM(experimentId, treatmentGroupId, controlGroupId, features);
                break;
            default:
                throw new IllegalArgumentException("不支持的因果推断方法: " + method);
        }
        if (!isBlockedResult(result)) {
            attachDataQualityCheck(result, statistics);
        }
        return result;
    }

    private Map<String, Object> buildAnalysisGateResult(String analysisType, String method, Statistics statistics) {
        if (statistics == null) {
            return buildBlockedAnalysisResult(analysisType, method,
                    "实验不存在或没有统计数据",
                    Collections.singletonList("未找到实验统计信息"),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    null);
        }

        Statistics.DataQualityCheck dataQualityCheck = statistics.getDataQualityCheck();
        if (dataQualityCheck == null || Boolean.TRUE.equals(dataQualityCheck.getAnalysisReady())) {
            return null;
        }
        return buildBlockedAnalysisResult(analysisType, method,
                "统计门禁未通过，无法执行因果分析",
                dataQualityCheck.getBlockingIssues(),
                dataQualityCheck.getWarnings(),
                Collections.emptyMap(),
                dataQualityCheck);
    }
    
    @Override
    public Map<String, Object> exportExperimentReport(String experimentId) {
        Map<String, Object> report = new HashMap<>();
        
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            report.put("error", "实验不存在");
            return report;
        }
        
        // 基本信息
        Map<String, Object> basicInfo = new HashMap<>();
        basicInfo.put("experimentId", experimentId);
        basicInfo.put("experimentName", metadata.getExperiment().getName());
        basicInfo.put("description", metadata.getExperiment().getDescription());
        basicInfo.put("status", metadata.getExperiment().getStatus().name());
        basicInfo.put("startTime", metadata.getExperiment().getStartTime());
        basicInfo.put("endTime", metadata.getExperiment().getEndTime());
        basicInfo.put("createTime", metadata.getExperiment().getCreateTime());
        basicInfo.put("creator", metadata.getExperiment().getCreator());
        report.put("basicInfo", basicInfo);
        
        // 流量配置
        Map<String, Object> trafficInfo = new HashMap<>();
        if (metadata.getTraffic() != null) {
            trafficInfo.put("totalTraffic", metadata.getTraffic().getTotalTraffic());
            trafficInfo.put("strategy", metadata.getTraffic().getStrategy().name());
            trafficInfo.put("allocation", metadata.getTraffic().getAllocation());
        }
        report.put("trafficConfig", trafficInfo);
        
        // 实验组配置
        report.put("groups", metadata.getGroups());
        
        // 统计数据
        Statistics statistics = getStatistics(experimentId);
        report.put("statistics", statistics);
        
        // 贝叶斯分析
        Map<String, Object> bayesianAnalysis = getBayesianAnalysis(experimentId);
        report.put("bayesianAnalysis", bayesianAnalysis);
        
        // 组间对比
        Map<String, Object> comparison = compareGroups(experimentId);
        report.put("groupComparison", comparison);

        Map<String, Object> dataSummary = generateDataSummary(statistics, bayesianAnalysis);
        report.put("dataSummary", dataSummary);

        List<Map<String, Object>> actionableRecommendations = generateActionableRecommendations(
                metadata, statistics, bayesianAnalysis);
        report.put("recommendations", actionableRecommendations);
        report.put("decisionContext", buildDecisionContext(statistics, bayesianAnalysis));
        
        // 生成结论和建议
        Map<String, Object> conclusions = generateConclusions(experimentId, statistics, bayesianAnalysis);
        report.put("conclusions", conclusions);
        
        // 报告元数据
        report.put("reportGeneratedAt", java.time.LocalDateTime.now());
        report.put("reportVersion", "1.1");
        
        return report;
    }

    @Override
    public ExperimentReportSnapshot createReportSnapshot(String experimentId, String generatedBy) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }

        Map<String, Object> report = exportExperimentReport(experimentId);
        Statistics statistics = getStatistics(experimentId);
        Map<String, Object> decisionContext = readDecisionContext(report);
        ExperimentMetadata.ConclusionStatus conclusionStatus = resolveConclusionStatus(metadata, statistics, decisionContext);

        ExperimentReportSnapshot snapshot = new ExperimentReportSnapshot();
        snapshot.setExperimentId(experimentId);
        snapshot.setSnapshotVersion(experimentReportSnapshotRepository.getNextVersion(experimentId));
        snapshot.setConclusionStatus(conclusionStatus);
        snapshot.setPrimaryMetricKey(readString(decisionContext, "primaryMetricKey"));
        snapshot.setBestPerformingGroup(readString(decisionContext, "bestPerformingGroup"));
        snapshot.setWinningVariant(readString(decisionContext, "winningVariant"));
        snapshot.setAnalysisReady(readBoolean(decisionContext, "analysisReady"));
        snapshot.setHasSrm(readHasSrm(statistics));
        snapshot.setBreachedGuardrails(readBreachedGuardrails(decisionContext));
        snapshot.setDecisionContext(decisionContext);
        snapshot.setReport(report);
        snapshot.setGeneratedBy(StringUtils.hasText(generatedBy) ? generatedBy : "system");
        snapshot.setGeneratedAt(LocalDateTime.now());

        return experimentReportSnapshotRepository.save(snapshot);
    }

    @Override
    public List<ExperimentReportSnapshot> listReportSnapshots(String experimentId) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        return experimentReportSnapshotRepository.listByExperimentId(experimentId);
    }
    
    /**
     * 生成实验结论和建议
     */
    private Map<String, Object> generateConclusions(String experimentId, Statistics statistics,
                                                     Map<String, Object> bayesianAnalysis) {
        Map<String, Object> conclusions = new HashMap<>();
        
        if (statistics == null || statistics.getSummary() == null) {
            conclusions.put("status", "数据不足");
            conclusions.put("recommendation", "需要收集更多数据");
            return conclusions;
        }
        
        Statistics.ExperimentSummary summary = statistics.getSummary();
        
        // 样本量评估
        Long totalVisitors = summary.getTotalVisitors();
        String sampleSizeStatus = buildSampleSizeStatus(statistics);
        conclusions.put("sampleSizeStatus", sampleSizeStatus);
        conclusions.put("totalVisitors", totalVisitors);
        conclusions.put("dataQualityCheck", statistics.getDataQualityCheck());
        conclusions.put("breachedGuardrails", summary.getBreachedGuardrails());
        
        // 最佳表现组
        conclusions.put("bestPerformingGroup", summary.getBestPerformingGroup());
        conclusions.put("bestConversionRate", summary.getBestConversionRate());
        
        // 贝叶斯分析结论
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            String bestVariant = null;
            double bestWinRate = 0.0;
            
            for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                if (entry.getValue() > bestWinRate) {
                    bestWinRate = entry.getValue();
                    bestVariant = entry.getKey();
                }
            }
            
            conclusions.put("bestVariantByBayesian", bestVariant);
            conclusions.put("bestVariantWinRate", bestWinRate);
            
            // 推荐操作
            String recommendation;
            if (bestWinRate >= 0.95) {
                recommendation = "强烈建议：变体 " + bestVariant + " 表现显著优于基准（胜率 " + 
                        String.format("%.1f%%", bestWinRate * 100) + "），可以停止实验并全量上线该变体";
            } else if (bestWinRate >= 0.80) {
                recommendation = "建议：变体 " + bestVariant + " 表现较好（胜率 " + 
                        String.format("%.1f%%", bestWinRate * 100) + "），可以考虑增大该变体的流量比例继续观察";
            } else if (bestWinRate <= 0.20) {
                recommendation = "建议放弃：变体 " + bestVariant + " 表现显著劣于基准（胜率 " + 
                        String.format("%.1f%%", bestWinRate * 100) + "），可以停止该变体并尝试其他方案";
            } else {
                recommendation = "继续实验：目前尚无变体表现出明显优势，建议继续收集数据";
            }
            if (summary.getBreachedGuardrails() != null && !summary.getBreachedGuardrails().isEmpty()) {
                recommendation = "检测到护栏指标异常，当前不建议直接按主指标结果推进上线";
            }
            conclusions.put("recommendation", applyQualityGateToConclusion(recommendation, statistics));
        } else {
            conclusions.put("recommendation", applyQualityGateToConclusion("需要收集更多数据才能给出可靠建议", statistics));
        }
        
        return conclusions;
    }
    
    @Override
    public Map<String, Object> getExperimentTimeline(String experimentId, String metricType, String granularity) {
        Map<String, Object> timeline = new HashMap<>();
        timeline.put("experimentId", experimentId);
        timeline.put("metricType", metricType);
        timeline.put("granularity", granularity);
        
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null) {
            timeline.put("error", "实验不存在");
            return timeline;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = metadata.getExperiment().getStartTime();
        if (start == null) {
            start = defaultTimelineStart(now, granularity);
        }
        LocalDateTime end = metadata.getExperiment().getEndTime();
        if (end == null || end.isAfter(now)) {
            end = now;
        }
        if (end.isBefore(start)) {
            end = start;
        }

        ChronoUnit bucketUnit = resolveBucketUnit(granularity);
        MetricDefinition timelineMetricDefinition = resolveTimelineMetricDefinition(metadata, metricType);
        LocalDateTime bucketStart = truncateToUnit(start, bucketUnit);
        LocalDateTime bucketEnd = truncateToUnit(end, bucketUnit);

        List<Map<String, Object>> dataPoints = new ArrayList<>();
        LocalDateTime cursor = bucketStart;
        while (!cursor.isAfter(bucketEnd)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", cursor);
            point.put("label", cursor.toString());

            Map<String, Double> groupValues = new LinkedHashMap<>();
            if (metadata.getGroups() != null) {
                for (String groupId : metadata.getGroups().keySet().stream().sorted().toList()) {
                    List<Event> events = dataService.getEvents(experimentId, groupId);
                    groupValues.put(groupId, calculateTimelineMetric(events, cursor, bucketUnit, metricType,
                            timelineMetricDefinition));
                }
            }
            point.put("values", groupValues);
            dataPoints.add(point);
            cursor = cursor.plus(1, bucketUnit);
        }

        timeline.put("dataPoints", dataPoints);
        timeline.put("note", "时间线数据基于真实事件聚合");
        return timeline;
    }

    private LocalDateTime defaultTimelineStart(LocalDateTime now, String granularity) {
        if ("HOUR".equalsIgnoreCase(granularity)) {
            return now.minusHours(23);
        }
        if ("WEEK".equalsIgnoreCase(granularity)) {
            return now.minusWeeks(7);
        }
        return now.minusDays(6);
    }

    private ChronoUnit resolveBucketUnit(String granularity) {
        if ("HOUR".equalsIgnoreCase(granularity)) {
            return ChronoUnit.HOURS;
        }
        if ("WEEK".equalsIgnoreCase(granularity)) {
            return ChronoUnit.WEEKS;
        }
        return ChronoUnit.DAYS;
    }

    private LocalDateTime truncateToUnit(LocalDateTime value, ChronoUnit unit) {
        if (unit == ChronoUnit.HOURS) {
            return value.truncatedTo(ChronoUnit.HOURS);
        }
        if (unit == ChronoUnit.WEEKS) {
            return value.toLocalDate().atStartOfDay().minusDays(value.getDayOfWeek().getValue() - 1L);
        }
        return value.toLocalDate().atStartOfDay();
    }

    private double calculateTimelineMetric(List<Event> events,
                                           LocalDateTime bucketStart,
                                           ChronoUnit bucketUnit,
                                           String metricType,
                                           MetricDefinition metricDefinition) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }

        LocalDateTime bucketEnd = bucketStart.plus(1, bucketUnit);
        List<Event> bucketEvents = events.stream()
                .filter(event -> event.getTimestamp() != null)
                .filter(event -> !event.getTimestamp().isBefore(bucketStart) && event.getTimestamp().isBefore(bucketEnd))
                .toList();

        if (bucketEvents.isEmpty()) {
            return 0.0;
        }

        if (metricDefinition != null) {
            return calculateTimelineMetricValue(bucketEvents, metricDefinition);
        }

        long views = countEventsByType(bucketEvents, Event.EVENT_TYPE_VIEW);
        long clicks = countEventsByType(bucketEvents, Event.EVENT_TYPE_CLICK);
        long conversions = countEventsByType(bucketEvents, Event.EVENT_TYPE_CONVERT);

        if ("CLICK_RATE".equalsIgnoreCase(metricType)) {
            return views > 0 ? (double) clicks / views : 0.0;
        }
        if ("VISITOR_COUNT".equalsIgnoreCase(metricType)) {
            return bucketEvents.stream()
                    .map(Event::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
        }
        return views > 0 ? (double) conversions / views : 0.0;
    }

    private MetricDefinition resolveTimelineMetricDefinition(ExperimentMetadata metadata, String metricType) {
        if (metadata == null || metadata.getMetricDefinitions() == null || metricType == null) {
            return null;
        }
        for (MetricDefinition metricDefinition : metadata.getMetricDefinitions()) {
            if (metricDefinition != null && metricType.equalsIgnoreCase(metricDefinition.getKey())) {
                return metricDefinition;
            }
        }
        return null;
    }

    private double calculateTimelineMetricValue(List<Event> bucketEvents, MetricDefinition metricDefinition) {
        long numerator = countEventsByType(bucketEvents, metricDefinition.getNumeratorEventType());
        if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.COUNT) {
            return numerator;
        }
        long denominator = resolveTimelineDenominator(bucketEvents, metricDefinition);
        return denominator > 0 ? (double) numerator / denominator : 0.0;
    }

    private long resolveTimelineDenominator(List<Event> bucketEvents, MetricDefinition metricDefinition) {
        return switch (metricDefinition.getDenominatorType()) {
            case VISITOR_COUNT -> bucketEvents.stream()
                    .map(Event::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            case EVENT_COUNT -> countEventsByType(bucketEvents, metricDefinition.getDenominatorEventType());
            case ASSIGNMENT_COUNT, EXPOSURE_COUNT -> 0L;
        };
    }

    private long countEventsByType(List<Event> events, String eventType) {
        if (events == null || eventType == null || eventType.isBlank()) {
            return 0L;
        }
        return events.stream()
                .filter(event -> event.getEventType() != null)
                .filter(event -> eventType.equalsIgnoreCase(event.getEventType()))
                .count();
    }
    
    @Override
    public Map<String, Object> getAIInsights(String experimentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        
        try {
            // 获取实验数据
            ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
            if (metadata == null) {
                result.put("error", "实验不存在");
                result.put("success", false);
                return result;
            }
            
            Statistics statistics = getStatistics(experimentId);
            Map<String, Object> bayesianAnalysis = getBayesianAnalysis(experimentId);
            
            String analysisPrompt = buildAIAnalysisPrompt(metadata, statistics, bayesianAnalysis);
            String aiAnalysis = callTongYiForAnalysis(analysisPrompt);
            
            result.put("experimentName", metadata.getExperiment().getName());
            result.put("status", metadata.getExperiment().getStatus().name());
            result.put("aiAnalysis", aiAnalysis);
            result.put("generatedAt", LocalDateTime.now());
            result.put("success", true);
            
            // 提取关键建议（基于统计数据和贝叶斯分析）
            Map<String, Object> keyInsights = extractKeyInsights(aiAnalysis, statistics, bayesianAnalysis);
            result.put("keyInsights", keyInsights);
            
            // 添加详细的数据摘要
            Map<String, Object> dataSummary = generateDataSummary(statistics, bayesianAnalysis);
            result.put("dataSummary", dataSummary);
            
            // 添加可操作的建议列表
            List<Map<String, Object>> actionableRecommendations = generateActionableRecommendations(
                    metadata, statistics, bayesianAnalysis);
            result.put("recommendations", actionableRecommendations);
            
        } catch (Exception e) {
            log.error("AI分析失败", e);
            result.put("error", "AI分析失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }
    
    /**
     * 生成数据摘要
     */
    private Map<String, Object> generateDataSummary(Statistics statistics, Map<String, Object> bayesianAnalysis) {
        Map<String, Object> summary = new HashMap<>();
        
        if (statistics != null && statistics.getSummary() != null) {
            Statistics.ExperimentSummary expSummary = statistics.getSummary();
            summary.put("totalVisitors", expSummary.getTotalVisitors());
            summary.put("totalEvents", expSummary.getTotalEvents());
            summary.put("totalAssignments", expSummary.getTotalAssignments());
            summary.put("totalExposures", expSummary.getTotalExposures());
            summary.put("overallConversionRate", expSummary.getOverallConversionRate());
            summary.put("bestPerformingGroup", expSummary.getBestPerformingGroup());
            summary.put("bestConversionRate", expSummary.getBestConversionRate());
            summary.put("primaryMetricKey", expSummary.getPrimaryMetricKey());
            summary.put("bestPrimaryMetricValue", expSummary.getBestPrimaryMetricValue());
            summary.put("breachedGuardrails", expSummary.getBreachedGuardrails());
        }
        
        if (bayesianAnalysis != null) {
            summary.put("winRates", bayesianAnalysis.get("winRates"));
            summary.put("baselineGroup", bayesianAnalysis.get("baselineGroup"));
            
            // 计算最大胜率
            double maxWinRate = 0.0;
            String winningVariant = null;
            if (bayesianAnalysis.containsKey("winRates")) {
                @SuppressWarnings("unchecked")
                Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
                for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                    if (entry.getValue() > maxWinRate) {
                        maxWinRate = entry.getValue();
                        winningVariant = entry.getKey();
                    }
                }
            }
            summary.put("maxWinRate", maxWinRate);
            summary.put("winningVariant", winningVariant);
            summary.put("isStatisticallySignificant", maxWinRate >= 0.95);
        }
        
        // 数据健康度评分
        long totalVisitors = statistics != null && statistics.getSummary() != null && 
                statistics.getSummary().getTotalVisitors() != null ? 
                statistics.getSummary().getTotalVisitors() : 0;
        
        int healthScore = 0;
        List<String> healthIssues = new ArrayList<>();
        
        if (totalVisitors >= 1000) healthScore += 40;
        else if (totalVisitors >= 500) healthScore += 25;
        else if (totalVisitors >= 100) healthScore += 10;
        else healthIssues.add("样本量不足");
        
        double maxWinRate = summary.containsKey("maxWinRate") ? (Double) summary.get("maxWinRate") : 0.0;
        if (maxWinRate >= 0.95) healthScore += 40;
        else if (maxWinRate >= 0.80) healthScore += 25;
        else if (maxWinRate >= 0.60) healthScore += 10;
        else healthIssues.add("置信度较低");
        
        if (statistics != null && statistics.getGroupStatistics() != null && 
                statistics.getGroupStatistics().size() >= 2) {
            healthScore += 20;
        } else {
            healthIssues.add("实验组数量不足");
        }
        
        summary.put("healthScore", healthScore);
        summary.put("healthIssues", healthIssues);
        summary.put("healthStatus", healthScore >= 80 ? "优秀" : healthScore >= 50 ? "良好" : healthScore >= 30 ? "一般" : "需改进");
        if (statistics != null && statistics.getDataQualityCheck() != null) {
            summary.put("dataQualityCheck", statistics.getDataQualityCheck());
            if (statistics.getDataQualityCheck().getBlockingIssues() != null) {
                healthIssues.addAll(statistics.getDataQualityCheck().getBlockingIssues());
            }
        }
        if (statistics != null && statistics.getSummary() != null
                && statistics.getSummary().getBreachedGuardrails() != null) {
            healthIssues.addAll(statistics.getSummary().getBreachedGuardrails());
        }
        
        return summary;
    }
    
    /**
     * 生成可操作的建议列表
     */
    private List<Map<String, Object>> generateActionableRecommendations(ExperimentMetadata metadata,
                                                                          Statistics statistics,
                                                                          Map<String, Object> bayesianAnalysis) {
        List<Map<String, Object>> recommendations = new ArrayList<>();

        if (!isAnalysisReady(statistics)) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("type", "FIX_DATA_QUALITY");
            rec.put("priority", "HIGH");
            rec.put("title", "先修复数据质量问题");
            rec.put("description", buildQualityGateRecommendation(statistics));
            rec.put("action", "优先处理 SRM、样本量或 assignment/exposure 缺失问题");
            rec.put("expectedImpact", "恢复实验结论可信度");
            recommendations.add(rec);
            return recommendations;
        }

        if (hasBreachedGuardrails(statistics)) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("type", "PROTECT_GUARDRAIL");
            rec.put("priority", "HIGH");
            rec.put("title", "护栏指标异常，暂停推进");
            rec.put("description", String.join("；", getBreachedGuardrails(statistics)));
            rec.put("action", "先分析负向影响来源，再决定是否继续实验或回滚");
            rec.put("expectedImpact", "避免因局部优化导致整体业务受损");
            recommendations.add(rec);
        }
        
        long totalVisitors = statistics != null && statistics.getSummary() != null && 
                statistics.getSummary().getTotalVisitors() != null ? 
                statistics.getSummary().getTotalVisitors() : 0;
        
        double maxWinRate = 0.0;
        String winningVariant = null;
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                if (entry.getValue() > maxWinRate) {
                    maxWinRate = entry.getValue();
                    winningVariant = entry.getKey();
                }
            }
        }
        
        // 根据数据状态生成建议
        if (maxWinRate >= 0.95 && totalVisitors >= 1000) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("type", "GRADUATE");
            rec.put("priority", "HIGH");
            rec.put("title", "全量发布最佳变体");
            rec.put("description", "变体 " + winningVariant + " 胜率达到 " + 
                    String.format("%.1f%%", maxWinRate * 100) + "，建议全量发布");
            rec.put("action", "发布变体 " + winningVariant);
            rec.put("expectedImpact", "预计提升转化率");
            recommendations.add(rec);
        } else if (maxWinRate >= 0.80 && totalVisitors >= 500) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("type", "INCREASE_TRAFFIC");
            rec.put("priority", "MEDIUM");
            rec.put("title", "增加领先变体流量");
            rec.put("description", "变体 " + winningVariant + " 表现领先，建议增加其流量比例");
            rec.put("action", "将 " + winningVariant + " 流量提升至50%");
            rec.put("expectedImpact", "加速达到统计显著性");
            recommendations.add(rec);
        }
        
        if (totalVisitors < 1000) {
            Map<String, Object> rec = new HashMap<>();
            rec.put("type", "COLLECT_DATA");
            rec.put("priority", "HIGH");
            rec.put("title", "继续收集数据");
            rec.put("description", "当前样本量 " + totalVisitors + "，建议继续收集至1000以上");
            rec.put("action", "保持实验运行，等待更多数据");
            rec.put("expectedImpact", "提高结论可信度");
            recommendations.add(rec);
        }
        
        // 检查实验运行时间
        LocalDateTime startTime = metadata.getExperiment().getStartTime();
        if (startTime != null) {
            long daysSinceStart = ChronoUnit.DAYS.between(startTime, LocalDateTime.now());
            if (daysSinceStart < 7) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("type", "EXTEND_DURATION");
                rec.put("priority", "MEDIUM");
                rec.put("title", "延长实验周期");
                rec.put("description", "实验仅运行 " + daysSinceStart + " 天，建议至少运行7天以覆盖完整业务周期");
                rec.put("action", "继续实验至少 " + (7 - daysSinceStart) + " 天");
                rec.put("expectedImpact", "排除周期性波动影响");
                recommendations.add(rec);
            }
        }
        
        // 添加监控建议
        Map<String, Object> monitorRec = new HashMap<>();
        monitorRec.put("type", "MONITOR");
        monitorRec.put("priority", "LOW");
        monitorRec.put("title", "持续监控指标");
        monitorRec.put("description", "定期查看主指标、护栏指标趋势和用户行为数据");
        monitorRec.put("action", "每日检查一次实验数据");
        monitorRec.put("expectedImpact", "及时发现异常");
        recommendations.add(monitorRec);
        
        return recommendations;
    }
    
    /**
     * 构建AI分析的Prompt
     */
    private String buildAIAnalysisPrompt(ExperimentMetadata metadata, Statistics statistics, 
                                          Map<String, Object> bayesianAnalysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的A/B测试数据分析专家。请根据以下实验数据，提供专业的分析报告和建议。\n\n");
        
        sb.append("## 实验基本信息\n");
        sb.append("- 实验名称：").append(metadata.getExperiment().getName()).append("\n");
        sb.append("- 实验描述：").append(metadata.getExperiment().getDescription()).append("\n");
        sb.append("- 实验状态：").append(metadata.getExperiment().getStatus()).append("\n");
        sb.append("- 开始时间：").append(metadata.getExperiment().getStartTime()).append("\n");
        sb.append("- 结束时间：").append(metadata.getExperiment().getEndTime()).append("\n\n");
        
        sb.append("## 实验组配置\n");
        if (metadata.getGroups() != null) {
            for (Map.Entry<String, com.pisces.common.model.ExperimentGroup> entry : metadata.getGroups().entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().getName())
                  .append(" (流量比例: ").append(entry.getValue().getTrafficRatio()).append(")\n");
            }
        }
        sb.append("\n");
        
        sb.append("## 统计数据\n");
        if (statistics != null && statistics.getGroupStatistics() != null) {
            for (Map.Entry<String, Statistics.GroupStatistics> entry : statistics.getGroupStatistics().entrySet()) {
                Statistics.GroupStatistics gs = entry.getValue();
                sb.append("### ").append(entry.getKey()).append(" (").append(gs.getGroupName()).append(")\n");
                sb.append("- 访客数：").append(gs.getUserCount()).append("\n");
                sb.append("- 浏览数：").append(gs.getViewCount()).append("\n");
                sb.append("- 点击数：").append(gs.getClickCount()).append("\n");
                sb.append("- 转化数：").append(gs.getConversionCount()).append("\n");
                sb.append("- 点击率：").append(String.format("%.2f%%", gs.getClickRate() * 100)).append("\n");
                sb.append("- 转化率：").append(String.format("%.2f%%", gs.getConversionRate() * 100)).append("\n");
                if (gs.getLiftRate() != null) {
                    sb.append("- 相对提升：").append(String.format("%.2f%%", gs.getLiftRate() * 100)).append("\n");
                }
                sb.append("\n");
            }
        }
        
        sb.append("## 贝叶斯分析结果\n");
        if (bayesianAnalysis != null) {
            if (bayesianAnalysis.containsKey("winRates")) {
                sb.append("各变体胜率：").append(bayesianAnalysis.get("winRates")).append("\n");
            }
            if (bayesianAnalysis.containsKey("earlyStopRecommendation")) {
                sb.append("提前终止建议：").append(bayesianAnalysis.get("earlyStopRecommendation")).append("\n");
            }
        }
        sb.append("\n");
        
        sb.append("## 请提供以下分析\n");
        sb.append("1. **数据质量评估**：样本量是否充足？数据是否存在异常？\n");
        sb.append("2. **效果分析**：哪个变体表现最好？效果提升是否显著？\n");
        sb.append("3. **统计可信度**：当前结果的置信度如何？是否需要更多数据？\n");
        sb.append("4. **风险评估**：全量上线最佳变体的风险有多大？\n");
        sb.append("5. **具体建议**：下一步应该怎么做？给出3-5条可操作的建议。\n");
        sb.append("6. **预计影响**：如果采用最佳方案，预计能带来多大的业务提升？\n\n");
        sb.append("请用专业但通俗易懂的语言回答，避免过多技术术语。");
        
        return sb.toString();
    }
    
    /**
     * 调用通义千问进行分析（带超时保护）
     * 超时设置为5分钟，因为大模型生成详细分析报告需要较长时间
     */
    private String callTongYiForAnalysis(String prompt) {
        // 打印配置参数
        log.info("========== 通义API请求参数 ==========");
        log.info("API启用状态: {}", tongYiConfig.isEnabled());
        log.info("API Key: {}", maskApiKey(tongYiConfig.getApiKey()));
        log.info("模型名称: {}", tongYiConfig.getModel());
        log.info("模型调用协议: {}", tongYiConfig.getApiMode());
        log.info("回退模型: {}", tongYiConfig.getFallbackModel());
        log.info("超时设置: {} 毫秒", tongYiConfig.getTimeout());
        log.info("Prompt长度: {} 字符", prompt != null ? prompt.length() : 0);
        log.info("=====================================");

        long startTime = System.currentTimeMillis();

        // 打印Prompt内容（前500字符）
        if (prompt != null && prompt.length() > 0) {
            String promptPreview = prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt;
            log.info("Prompt预览:\n{}", promptPreview);
        }

        // 使用 CompletableFuture 添加超时保护
        try {
            java.util.concurrent.CompletableFuture<String> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> textGenerationClient().generateText(
                            "你是一位资深的A/B测试数据分析专家，擅长从实验数据中提取洞察并给出专业建议。",
                            prompt,
                            "通义实验分析"));

            // 设置5分钟（300秒）超时
            log.info("等待通义API响应，超时时间: 5分钟...");
            String result = future.get(300, java.util.concurrent.TimeUnit.SECONDS);

            long elapsed = System.currentTimeMillis() - startTime;

            if (StringUtils.hasText(result)) {
                log.info("========== 通义API调用成功 ==========");
                log.info("总耗时: {} 毫秒 ({} 秒)", elapsed, elapsed / 1000);
                log.info("响应长度: {} 字符", result.length());
                log.info("=====================================");
                return result;
            }
            
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI分析返回空结果");
            
        } catch (java.util.concurrent.TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("========== 通义API调用超时 ==========");
            log.error("已等待: {} 毫秒 ({} 秒)", elapsed, elapsed / 1000);
            log.error("超时限制: 5分钟（300秒）");
            log.error("可能原因:");
            log.error("  1. 网络连接问题，无法访问 dashscope.aliyuncs.com");
            log.error("  2. API Key无效或已过期");
            log.error("  3. 模型繁忙或服务不可用");
            log.error("  4. Prompt过长导致处理时间过长");
            log.error("=====================================");
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI分析超时");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("========== 通义API执行异常 ==========");
            log.error("异常类型: {}", cause != null ? cause.getClass().getName() : e.getClass().getName());
            log.error("异常信息: {}", cause != null ? cause.getMessage() : e.getMessage());
            if (cause != null) {
                cause.printStackTrace();
            }
            log.error("=====================================");
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE,
                    "通义AI分析执行失败: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            log.error("========== 通义API调用失败 ==========");
            log.error("异常类型: {}", e.getClass().getName());
            log.error("异常信息: {}", e.getMessage());
            e.printStackTrace();
            log.error("=====================================");
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI分析失败: " + e.getMessage());
        }
    }
    
    /**
     * 隐藏API Key中间部分，只显示前4位和后4位
     */
    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return "(空)";
        }
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
    
    /**
     * 从AI分析中提取关键洞察
     */
    private Map<String, Object> extractKeyInsights(String aiAnalysis, Statistics statistics,
                                                    Map<String, Object> bayesianAnalysis) {
        Map<String, Object> insights = new HashMap<>();
        
        // 基于统计数据的关键指标
        if (statistics != null && statistics.getSummary() != null) {
            Statistics.ExperimentSummary summary = statistics.getSummary();
            insights.put("winningVariant", summary.getBestPerformingGroup());
            insights.put("conversionImprovement", summary.getBestConversionRate());
        }
        
        // 基于贝叶斯分析的置信度
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            double maxWinRate = 0.0;
            for (Double rate : winRates.values()) {
                if (rate > maxWinRate) {
                    maxWinRate = rate;
                }
            }
            insights.put("confidenceLevel", maxWinRate);
            insights.put("readyForDecision", maxWinRate >= 0.95);
        }
        
        // 推荐操作
        List<String> actions = new ArrayList<>();
        actions.add("查看完整分析报告");
        actions.add("导出实验数据");
        if (insights.containsKey("readyForDecision") && Boolean.TRUE.equals(insights.get("readyForDecision"))) {
            actions.add("全量发布最佳变体");
        } else {
            actions.add("继续收集数据");
        }
        insights.put("recommendedActions", actions);
        
        return insights;
    }
    
    @Override
    public Map<String, Object> getAIExperimentDesign(String businessScenario, String targetMetric,
                                                      List<String> constraints) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 构建实验设计prompt
            String designPrompt = buildExperimentDesignPrompt(businessScenario, targetMetric, constraints);
            
            // 调用AI生成实验设计方案
            String aiDesign = callTongYiForDesign(designPrompt);
            
            result.put("businessScenario", businessScenario);
            result.put("targetMetric", targetMetric);
            result.put("constraints", constraints);
            result.put("aiDesign", aiDesign);
            result.put("generatedAt", LocalDateTime.now());
            result.put("success", true);
            
            // 生成推荐的实验配置
            Map<String, Object> recommendedConfig = generateRecommendedConfig(businessScenario, targetMetric);
            result.put("recommendedConfig", recommendedConfig);
            
        } catch (Exception e) {
            log.error("AI实验设计失败", e);
            result.put("error", "AI实验设计失败: " + e.getMessage());
            result.put("success", false);
        }
        
        return result;
    }
    
    /**
     * 构建实验设计Prompt
     */
    private String buildExperimentDesignPrompt(String businessScenario, String targetMetric,
                                                List<String> constraints) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位A/B测试实验设计专家。请根据以下业务场景设计一个A/B测试方案。\n\n");
        
        sb.append("## 业务场景\n");
        sb.append(businessScenario).append("\n\n");
        
        sb.append("## 目标指标\n");
        sb.append(targetMetric).append("\n\n");
        
        if (constraints != null && !constraints.isEmpty()) {
            sb.append("## 约束条件\n");
            for (String constraint : constraints) {
                sb.append("- ").append(constraint).append("\n");
            }
            sb.append("\n");
        }
        
        sb.append("## 请提供以下设计内容\n");
        sb.append("1. **实验假设**：明确的假设陈述\n");
        sb.append("2. **实验组设计**：建议几个实验组，每组的核心变化是什么\n");
        sb.append("3. **流量分配**：各组建议的流量比例\n");
        sb.append("4. **样本量估算**：需要多少样本才能得出可靠结论\n");
        sb.append("5. **实验周期**：建议运行多长时间\n");
        sb.append("6. **成功标准**：如何判断实验成功\n");
        sb.append("7. **风险提示**：需要注意的潜在风险\n");
        sb.append("8. **数据采集点**：需要埋点采集的关键事件\n\n");
        sb.append("请用结构化的格式回答，便于执行。");
        
        return sb.toString();
    }
    
    /**
     * 调用通义千问进行实验设计
     */
    private String callTongYiForDesign(String prompt) {
        try {
            return textGenerationClient().generateText(
                    "你是一位资深的A/B测试实验设计专家，擅长设计科学严谨的实验方案。",
                    prompt,
                    "通义实验设计");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用通义API进行实验设计失败", e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI实验设计失败: " + e.getMessage());
        }
    }

    private TongYiTextGenerationClient textGenerationClient() {
        if (tongYiTextGenerationClient != null) {
            return tongYiTextGenerationClient;
        }
        return new TongYiTextGenerationClient(tongYiConfig);
    }
    
    /**
     * 生成推荐的实验配置
     */
    private Map<String, Object> generateRecommendedConfig(String businessScenario, String targetMetric) {
        Map<String, Object> config = new HashMap<>();
        
        // 推荐实验组配置
        List<Map<String, Object>> groups = new ArrayList<>();
        
        Map<String, Object> controlGroup = new HashMap<>();
        controlGroup.put("id", "control");
        controlGroup.put("name", "对照组");
        controlGroup.put("trafficRatio", 0.34);
        groups.add(controlGroup);
        
        Map<String, Object> variantB = new HashMap<>();
        variantB.put("id", "variant_b");
        variantB.put("name", "实验组B");
        variantB.put("trafficRatio", 0.33);
        groups.add(variantB);
        
        Map<String, Object> variantC = new HashMap<>();
        variantC.put("id", "variant_c");
        variantC.put("name", "实验组C");
        variantC.put("trafficRatio", 0.33);
        groups.add(variantC);
        
        config.put("groups", groups);
        
        // 推荐实验时长
        config.put("recommendedDuration", "14天");
        config.put("minimumSampleSize", 3000);
        config.put("trafficStrategy", "HASH");
        
        return config;
    }
    
    @Override
    public Map<String, Object> autoGraduateDecision(String experimentId) {
        getAccessibleExperimentMetadataOrThrow(experimentId);
        AIDecisionService aiDecisionService = aiDecisionServiceProvider.getObject();
        AIGraduationDecisionResponse response = aiDecisionService.decideGraduation(experimentId);
        return buildAutoGraduateBridgeResult(experimentId, response);
    }

    private Map<String, Object> buildAutoGraduateBridgeResult(String experimentId,
                                                              AIGraduationDecisionResponse response) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        result.put("decisionType", response.getDecisionType());
        result.put("guardrailStatus", response.getGuardrailStatus());
        result.put("decision", response.getDecision());
        result.put("confidence", response.getConfidence());
        result.put("riskFlags", response.getRiskFlags());
        result.put("summary", response.getSummary());
        result.put("evidence", response.getEvidence());
        result.put("success", true);
        return result;
    }
    
    @Override
    public Map<String, Object> predictExperimentCompletion(String experimentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        
        try {
            ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
            if (metadata == null) {
                result.put("error", "实验不存在");
                return result;
            }
            
            Statistics statistics = getStatistics(experimentId);
            Map<String, Object> bayesianAnalysis = getBayesianAnalysis(experimentId);
            result.put("decisionContext", buildDecisionContext(statistics, bayesianAnalysis));
            attachDataQualityCheck(result, statistics);
            if (!isAnalysisReady(statistics)) {
                result.put("status", "BLOCKED_BY_QUALITY_GATE");
                result.put("message", buildQualityGateRecommendation(statistics));
                result.put("estimatedDaysRemaining", -1);
                result.put("accelerationTips", List.of("先修复数据质量问题，再重新评估实验完成时间"));
                result.put("success", true);
                return result;
            }
            if (hasBreachedGuardrails(statistics)) {
                result.put("status", "BLOCKED_BY_GUARDRAIL");
                result.put("message", "护栏指标异常，当前不建议仅依据主指标预测完成时间");
                result.put("estimatedDaysRemaining", -1);
                result.put("accelerationTips", List.of("先分析并修复护栏指标下降问题"));
                result.put("breachedGuardrails", getBreachedGuardrails(statistics));
                result.put("success", true);
                return result;
            }
            
            // 计算当前进度
            double currentConfidence = 0.0;
            if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
                @SuppressWarnings("unchecked")
                Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
                for (Double rate : winRates.values()) {
                    if (rate > currentConfidence) {
                        currentConfidence = rate;
                    }
                }
            }
            
            // 计算进度百分比
            double targetConfidence = 0.95;
            double progress = Math.min(1.0, currentConfidence / targetConfidence);
            result.put("currentProgress", progress);
            result.put("currentConfidence", currentConfidence);
            result.put("targetConfidence", targetConfidence);
            result.put("primaryMetricKey", statistics != null && statistics.getSummary() != null
                    ? statistics.getSummary().getPrimaryMetricKey() : null);
            
            // 计算当前样本收集速度
            long totalVisitors = 0;
            if (statistics != null && statistics.getSummary() != null) {
                Long visitors = statistics.getSummary().getTotalVisitors();
                totalVisitors = visitors != null ? visitors : 0;
            }
            
            LocalDateTime startTime = metadata.getExperiment().getStartTime();
            long daysRunning = 1;
            if (startTime != null) {
                daysRunning = Math.max(1, ChronoUnit.DAYS.between(startTime, LocalDateTime.now()));
            }
            
            double dailyVisitorRate = (double) totalVisitors / daysRunning;
            result.put("totalVisitors", totalVisitors);
            result.put("daysRunning", daysRunning);
            result.put("dailyVisitorRate", dailyVisitorRate);
            
            // 预测完成时间
            if (currentConfidence >= targetConfidence) {
                result.put("status", "COMPLETED");
                result.put("message", "实验已达到统计显著性，可以做出决策");
                result.put("estimatedDaysRemaining", 0);
            } else if (progress > 0.1) {
                // 基于当前进度线性外推
                double remainingProgress = 1.0 - progress;
                int estimatedDaysRemaining = (int) Math.ceil(daysRunning * remainingProgress / progress);
                estimatedDaysRemaining = Math.min(estimatedDaysRemaining, 90); // 最多预测90天
                
                result.put("status", "IN_PROGRESS");
                result.put("estimatedDaysRemaining", estimatedDaysRemaining);
                result.put("estimatedCompletionDate", LocalDateTime.now().plusDays(estimatedDaysRemaining));
                result.put("message", String.format("预计还需 %d 天达到统计显著性", estimatedDaysRemaining));
            } else {
                result.put("status", "EARLY_STAGE");
                result.put("message", "实验处于早期阶段，需要更多数据才能准确预测");
                result.put("estimatedDaysRemaining", -1);
            }
            
            // 提供加速建议
            List<String> accelerationTips = new ArrayList<>();
            if (dailyVisitorRate < 100) {
                accelerationTips.add("当前日均流量较低，考虑增加实验流量比例");
            }
            if (metadata.getGroups() != null && metadata.getGroups().size() > 3) {
                accelerationTips.add("实验组较多，考虑减少变体数量以加快收敛");
            }
            result.put("accelerationTips", accelerationTips);

            result.put("success", true);

        } catch (Exception e) {
            log.error("预测实验完成时间失败", e);
            result.put("error", "预测失败: " + e.getMessage());
            result.put("success", false);
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // SRM 检测
    // ─────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> detectSRM(String experimentId) {
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().size() < 2) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "实验不存在或实验组数量不足");
            return err;
        }

        List<String> groupIds = new ArrayList<>(metadata.getGroups().keySet());
        long[] observed = new long[groupIds.size()];
        double[] expectedRatios = new double[groupIds.size()];

        for (int i = 0; i < groupIds.size(); i++) {
            String gid = groupIds.get(i);
            observed[i] = dataService.getAssignmentCount(experimentId, gid);
            com.pisces.common.model.ExperimentGroup g = metadata.getGroups().get(gid);
            expectedRatios[i] = g != null && g.getTrafficRatio() != null
                    ? g.getTrafficRatio() : 1.0 / groupIds.size();
        }

        boolean hasAssignmentFacts = Arrays.stream(observed).anyMatch(count -> count > 0);
        if (!hasAssignmentFacts) {
            for (int i = 0; i < groupIds.size(); i++) {
                observed[i] = dataService.getVisitorCount(experimentId, groupIds.get(i));
            }
        }

        Map<String, Object> result = StatisticalUtils.detectSRM(observed, expectedRatios);
        result.put("experimentId", experimentId);
        result.put("groupIds", groupIds);

        if (Boolean.TRUE.equals(result.get("hasSRM"))) {
            log.warn("SRM 检测：实验 {} 存在样本比例不匹配，结论不可信！", experimentId);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // 序贯检验（SPRT）
    // ─────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> sequentialTest(String experimentId, String variantGroupId,
                                               String baselineGroupId, Double mde,
                                               Double alpha, Double beta) {
        double effectSize = mde   != null ? mde   : DEFAULT_GATE_MDE;
        double alphaVal   = alpha != null ? alpha : DEFAULT_GATE_ALPHA;
        double betaVal    = beta  != null ? beta  : 0.20;
        Statistics statistics = getStatistics(experimentId);
        ExperimentMetadata metadata = getAccessibleExperimentMetadata(experimentId);
        MetricDefinition primaryMetricDefinition = metadata != null
                ? resolvePrimaryMetric(resolveMetricDefinitions(metadata)) : null;
        MetricDefinition inferenceMetricDefinition = resolveRateMetricForInference(primaryMetricDefinition);
        Statistics.GroupStatistics variantGroupStats = statistics != null
                ? statistics.getGroupStatistics().get(variantGroupId) : null;
        Statistics.GroupStatistics baselineGroupStats = statistics != null
                ? statistics.getGroupStatistics().get(baselineGroupId) : null;

        long n1 = resolveMetricDenominator(inferenceMetricDefinition, variantGroupStats, experimentId, variantGroupId);
        long x1 = resolveMetricNumerator(inferenceMetricDefinition, variantGroupStats, experimentId, variantGroupId);
        long n2 = resolveMetricDenominator(inferenceMetricDefinition, baselineGroupStats, experimentId, baselineGroupId);
        long x2 = resolveMetricNumerator(inferenceMetricDefinition, baselineGroupStats, experimentId, baselineGroupId);

        double p0 = n2 > 0 ? (double) x2 / n2 : 0.05;

        Map<String, Object> result = StatisticalUtils.sprtTest(n1, x1, n2, x2, p0, effectSize, alphaVal, betaVal);
        result.put("experimentId", experimentId);
        result.put("variantGroupId", variantGroupId);
        result.put("baselineGroupId", baselineGroupId);
        result.put("variantSampleSize", n1);
        result.put("baselineSampleSize", n2);
        result.put("metricKeyUsed", inferenceMetricDefinition.getKey());
        if (primaryMetricDefinition != null
                && !inferenceMetricDefinition.getKey().equals(primaryMetricDefinition.getKey())) {
            result.put("metricAlignmentWarning",
                    "当前序贯检验只支持比例型主指标，已回退到 conversion_rate 口径");
        }
        attachDataQualityCheck(result, statistics);
        if (!isAnalysisReady(statistics)) {
            result.put("decision", "CONTINUE");
            result.put("canStop", false);
            result.put("qualityGateBlocked", true);
            result.put("interpretation", applyQualityGateToConclusion(
                    String.valueOf(result.get("interpretation")), statistics));
        }
        return result;
    }

    private void attachDataQualityCheck(Map<String, Object> result, Statistics statistics) {
        Statistics.DataQualityCheck dataQualityCheck = statistics != null ? statistics.getDataQualityCheck() : null;
        result.put("dataQualityCheck", dataQualityCheck);
        result.put("analysisReady", dataQualityCheck == null || Boolean.TRUE.equals(dataQualityCheck.getAnalysisReady()));
    }

    private Map<String, Object> validateCausalInputContract(String method, Map<String, Object> params) {
        String normalizedMethod = normalizeMethod(method);
        Map<String, Object> safeParams = params != null ? params : Collections.emptyMap();
        if ("DID".equals(normalizedMethod)) {
            List<String> requiredFields = Arrays.asList("beforePeriodStart", "beforePeriodEnd",
                    "afterPeriodStart", "afterPeriodEnd");
            List<String> missingFields = new ArrayList<>();
            for (String field : requiredFields) {
                Object value = safeParams.get(field);
                if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                    missingFields.add(field);
                }
            }
            if (!missingFields.isEmpty()) {
                Map<String, Object> contract = new LinkedHashMap<>();
                contract.put("requiredInputs", requiredFields);
                contract.put("providedInputs", safeParams.keySet());
                contract.put("missingInputs", missingFields);
                return buildBlockedAnalysisResult("CAUSAL_INFERENCE", method,
                        "DID 需要完整的 pre/post 时间窗参数",
                        missingFields,
                        Collections.emptyList(),
                        contract,
                        null);
            }
        } else if ("PSM".equals(normalizedMethod)) {
            Object userFeaturesObject = safeParams.get("userFeatures");
            if (!(userFeaturesObject instanceof List)) {
                Map<String, Object> contract = new LinkedHashMap<>();
                contract.put("requiredInputs", Collections.singletonList("userFeatures"));
                contract.put("supportedCovariates", Arrays.asList("viewCount", "clickCount", "eventCount", "rank"));
                contract.put("providedInputs", safeParams.keySet());
                return buildBlockedAnalysisResult("CAUSAL_INFERENCE", method,
                        "PSM 需要显式协变量输入，当前请求无效",
                        Collections.singletonList("userFeatures 必须是非空列表"),
                        Collections.emptyList(),
                        contract,
                        null);
            }
            @SuppressWarnings("unchecked")
            List<Object> rawUserFeatures = (List<Object>) userFeaturesObject;
            List<String> userFeatures = new ArrayList<>();
            for (Object feature : rawUserFeatures) {
                if (feature != null && StringUtils.hasText(String.valueOf(feature))) {
                    userFeatures.add(String.valueOf(feature).trim());
                }
            }
            if (userFeatures.isEmpty()) {
                Map<String, Object> contract = new LinkedHashMap<>();
                contract.put("requiredInputs", Collections.singletonList("userFeatures"));
                contract.put("supportedCovariates", Arrays.asList("viewCount", "clickCount", "eventCount", "rank"));
                contract.put("providedInputs", safeParams.keySet());
                return buildBlockedAnalysisResult("CAUSAL_INFERENCE", method,
                        "PSM 需要显式协变量输入，当前请求无效",
                        Collections.singletonList("userFeatures 必须是非空列表"),
                        Collections.emptyList(),
                        contract,
                        null);
            }
        } else if (normalizedMethod != null && !"DID".equals(normalizedMethod)) {
            return buildBlockedAnalysisResult("CAUSAL_INFERENCE", method,
                    "当前仅支持 DID 和 PSM",
                    Collections.singletonList("不支持的因果推断方法: " + normalizedMethod),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    null);
        }
        return null;
    }

    private Map<String, Object> buildBlockedAnalysisResult(String analysisType, String method,
                                                           String reason, List<String> blockingIssues,
                                                           List<String> warnings, Map<String, Object> contract,
                                                           Statistics.DataQualityCheck dataQualityCheck) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysisType", analysisType);
        result.put("method", normalizeMethod(method));
        result.put("status", "BLOCKED");
        result.put("blocked", true);
        result.put("analysisReady", false);
        result.put("reason", reason);
        result.put("blockingIssues", blockingIssues != null ? blockingIssues : Collections.emptyList());
        result.put("warnings", warnings != null ? warnings : Collections.emptyList());
        if (contract != null && !contract.isEmpty()) {
            result.put("inputContract", contract);
        }
        if (dataQualityCheck != null) {
            result.put("dataQualityCheck", dataQualityCheck);
        }
        return result;
    }

    private boolean isBlockedResult(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("blocked"));
    }

    private String normalizeMethod(String method) {
        return method == null ? null : method.trim().toUpperCase();
    }

    private boolean isAnalysisReady(Statistics statistics) {
        if (statistics == null || statistics.getDataQualityCheck() == null) {
            return true;
        }
        return Boolean.TRUE.equals(statistics.getDataQualityCheck().getAnalysisReady());
    }

    private String applyQualityGateToConclusion(String originalConclusion, Statistics statistics) {
        if (isAnalysisReady(statistics)) {
            return originalConclusion;
        }
        return buildQualityGateRecommendation(statistics) + "；" + originalConclusion;
    }

    private String buildQualityGateRecommendation(Statistics statistics) {
        if (statistics == null || statistics.getDataQualityCheck() == null
                || statistics.getDataQualityCheck().getBlockingIssues() == null
                || statistics.getDataQualityCheck().getBlockingIssues().isEmpty()) {
            return "数据质量门禁未通过";
        }
        return "数据质量门禁未通过：" + String.join("；", statistics.getDataQualityCheck().getBlockingIssues());
    }

    private String buildSampleSizeStatus(Statistics statistics) {
        Statistics.ExperimentSummary summary = statistics.getSummary();
        Long totalVisitors = summary.getTotalVisitors();
        Statistics.DataQualityCheck dataQualityCheck = statistics.getDataQualityCheck();
        if (dataQualityCheck != null && dataQualityCheck.getRequiredSampleSizePerGroup() != null) {
            if (Boolean.TRUE.equals(dataQualityCheck.getSampleSizeReached())) {
                return String.format("样本量达到建议阈值（每组至少 %d）", dataQualityCheck.getRequiredSampleSizePerGroup());
            }
            return String.format("样本量未达到建议阈值（每组建议至少 %d）",
                    dataQualityCheck.getRequiredSampleSizePerGroup());
        }
        if (totalVisitors == null || totalVisitors < 100) {
            return "样本量不足（< 100），结果可能不可靠";
        }
        if (totalVisitors < 1000) {
            return "样本量较小（100-1000），建议继续收集数据";
        }
        return "样本量充足（> 1000），结果较为可靠";
    }

    private boolean hasBreachedGuardrails(Statistics statistics) {
        return statistics != null && statistics.getSummary() != null
                && statistics.getSummary().getBreachedGuardrails() != null
                && !statistics.getSummary().getBreachedGuardrails().isEmpty();
    }

    private List<String> getBreachedGuardrails(Statistics statistics) {
        if (!hasBreachedGuardrails(statistics)) {
            return new ArrayList<>();
        }
        return statistics.getSummary().getBreachedGuardrails();
    }

    private ExperimentMetadata.ConclusionStatus resolveConclusionStatus(ExperimentMetadata metadata,
                                                                       Statistics statistics,
                                                                       Map<String, Object> decisionContext) {
        if (metadata != null && metadata.getConclusionStatus() != null
                && Set.of(ExperimentMetadata.ConclusionStatus.GRADUATED,
                ExperimentMetadata.ConclusionStatus.REJECTED).contains(metadata.getConclusionStatus())) {
            return metadata.getConclusionStatus();
        }
        if (!isAnalysisReady(statistics)) {
            return ExperimentMetadata.ConclusionStatus.NOT_READY;
        }
        if (!StringUtils.hasText(readString(decisionContext, "bestPerformingGroup"))
                && !StringUtils.hasText(readString(decisionContext, "winningVariant"))) {
            return ExperimentMetadata.ConclusionStatus.RUNNING;
        }
        return ExperimentMetadata.ConclusionStatus.READY_FOR_REVIEW;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDecisionContext(Map<String, Object> report) {
        if (report == null || !(report.get("decisionContext") instanceof Map<?, ?> rawDecisionContext)) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) rawDecisionContext;
    }

    private String readString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof String stringValue ? stringValue : null;
    }

    private Boolean readBoolean(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readBreachedGuardrails(Map<String, Object> decisionContext) {
        Object value = decisionContext.get("breachedGuardrails");
        if (value instanceof List<?> listValue) {
            return (List<String>) listValue;
        }
        return new ArrayList<>();
    }

    private Boolean readHasSrm(Statistics statistics) {
        if (statistics == null || statistics.getDataQualityCheck() == null) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE.equals(statistics.getDataQualityCheck().getHasSrm());
    }

    private Map<String, Object> buildDecisionContext(Statistics statistics, Map<String, Object> bayesianAnalysis) {
        Map<String, Object> decisionContext = new HashMap<>();
        if (statistics != null && statistics.getSummary() != null) {
            decisionContext.put("primaryMetricKey", statistics.getSummary().getPrimaryMetricKey());
            decisionContext.put("bestPerformingGroup", statistics.getSummary().getBestPerformingGroup());
            decisionContext.put("bestPrimaryMetricValue", statistics.getSummary().getBestPrimaryMetricValue());
            decisionContext.put("breachedGuardrails", statistics.getSummary().getBreachedGuardrails());
        }
        if (statistics != null) {
            decisionContext.put("analysisReady", isAnalysisReady(statistics));
            decisionContext.put("dataQualityCheck", statistics.getDataQualityCheck());
        }
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            String winningVariant = null;
            double maxWinRate = 0.0;
            for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                if (entry.getValue() > maxWinRate) {
                    maxWinRate = entry.getValue();
                    winningVariant = entry.getKey();
                }
            }
            decisionContext.put("winningVariant", winningVariant);
            decisionContext.put("maxWinRate", maxWinRate);
        }
        return decisionContext;
    }
}
