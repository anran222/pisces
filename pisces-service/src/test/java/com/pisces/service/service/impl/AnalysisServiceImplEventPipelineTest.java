package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.request.EventReplayPlanRequest;
import com.pisces.common.response.EventPipelineOperationResponse;
import com.pisces.common.response.EventReplayJobResponse;
import com.pisces.common.response.EventReplayPlanResponse;
import com.pisces.service.entity.EventInboxStatusCountEntity;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventPipelineRebuildResult;
import com.pisces.service.event.EventReplayJobRecord;
import com.pisces.service.event.EventReplayProgressReporter;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.EventInboxRepository;
import com.pisces.service.repository.EventMaterializationRepository;
import com.pisces.service.repository.EventReplayJobRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件管道治理测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/29 23:12
 */
class AnalysisServiceImplEventPipelineTest {

    @Test
    void drainEventPipelineShouldProcessExperimentInboxUntilDone() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventInboxRepository eventInboxRepository = mock(EventInboxRepository.class);
        EventInboxConsumer eventInboxConsumer = mock(EventInboxConsumer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventInboxRepository", eventInboxRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxConsumer", eventInboxConsumer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineDrainMaxRounds", 5);
        ReflectionTestUtils.setField(analysisService, "eventPipelineDrainMaxWaitMs", 1L);
        ReflectionTestUtils.setField(analysisService, "eventPipelineDrainIdleWaitMs", 1L);

        when(configService.getExperimentConfig("exp_1")).thenReturn(new ExperimentMetadata());
        when(eventInboxRepository.countByExperimentIdGroupByStatus("exp_1"))
                .thenReturn(statusCounts(EventInboxConstants.STATUS_PENDING, 2L))
                .thenReturn(statusCounts(EventInboxConstants.STATUS_PENDING, 1L,
                        EventInboxConstants.STATUS_DONE, 1L))
                .thenReturn(statusCounts(EventInboxConstants.STATUS_DONE, 2L));
        when(eventInboxConsumer.processDueRecords("exp_1")).thenReturn(1).thenReturn(1);

        EventPipelineOperationResponse response = analysisService.drainEventPipeline("exp_1", "tester");

        assertThat(response.getExperimentId()).isEqualTo("exp_1");
        assertThat(response.getOperation()).isEqualTo("DRAIN_INBOX");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAffectedCount()).isEqualTo(2L);
        assertThat(response.getMessage()).contains("已同步物化");
        verify(eventInboxConsumer, times(2)).processDueRecords("exp_1");
    }

    @Test
    void replayEventPipelineShouldCreateAndCompleteReplayJob() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);

        EventPipelineRebuildResult rebuildResult = new EventPipelineRebuildResult();
        rebuildResult.setGroupCount(2L);
        rebuildResult.setEventCount(10L);
        rebuildResult.setExposureCount(4L);
        rebuildResult.setMabRewardCount(6L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(true);
        when(eventInboxMaterializer.rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class))).thenReturn(rebuildResult);

        EventPipelineOperationResponse response = analysisService.replayEventPipeline("exp_replay", "tester");

        assertThat(response.getOperation()).isEqualTo("REPLAY_DERIVED");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getReplayJobId()).startsWith("replay_");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_RUNNING);
        assertThat(response.getReplayMode()).isEqualTo("FULL_DERIVED_REBUILD");
        assertThat(response.getFullDerivedReplay()).isTrue();
        assertThat(response.getIncludeEvents()).isTrue();
        assertThat(response.getIncludeExposures()).isTrue();
        assertThat(response.getEventTypes()).isEmpty();
        assertThat(response.getAffectedCount()).isZero();
        verify(replayJobRepository).expireStaleRunningJobs(eq("exp_replay"), any(), any(), anyString());
        verify(replayJobRepository).createRunningJob(argThat(record ->
                "exp_replay".equals(record.getExperimentId())
                        && "tester".equals(record.getOperator())
                        && EventReplayJobRecord.STATUS_RUNNING.equals(record.getJobStatus())
                        && "exp_replay".equals(record.getActiveKey())
                        && "FULL_DERIVED_REBUILD".equals(record.getReplayMode())
                        && Boolean.TRUE.equals(record.getFullDerivedReplay())
                        && Boolean.TRUE.equals(record.getIncludeEvents())
                        && Boolean.TRUE.equals(record.getIncludeExposures())
                        && record.getEventTypes().isEmpty()
                        && record.getReplayJobId() != null
                        && record.getReplayJobId().startsWith("replay_")));
        verify(replayJobRepository).markSucceeded(eq(response.getReplayJobId()), eq(14L), eq(10L), eq(4L),
                eq(2L), eq(6L), any());
        verify(eventInboxMaterializer).rebuildDerivedData(eq("exp_replay"), eq(response.getReplayJobId()),
                any(EventReplayProgressReporter.class));
    }

    @Test
    void replayEventPipelineShouldFinishFullRebuildBeforeHonoringCancellation() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);

        EventReplayJobRecord runningJob = runningReplayJobRecord("replay_cancelled", "exp_replay");
        EventReplayJobRecord cancelRequestedJob = runningReplayJobRecord("replay_cancelled", "exp_replay");
        cancelRequestedJob.setJobStatus(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(false);
        when(replayJobRepository.findByExperimentIdAndReplayJobId(eq("exp_replay"), anyString()))
                .thenReturn(runningJob)
                .thenReturn(cancelRequestedJob);
        when(eventInboxMaterializer.rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class))).thenAnswer(invocation -> {
                    EventReplayProgressReporter progressReporter = invocation.getArgument(2);
                    EventPipelineRebuildResult partialResult = new EventPipelineRebuildResult();
                    partialResult.setGroupCount(1L);
                    partialResult.setEventCount(1L);
                    assertThat(progressReporter.report(partialResult)).isTrue();

                    EventPipelineRebuildResult rebuildResult = new EventPipelineRebuildResult();
                    rebuildResult.setGroupCount(2L);
                    rebuildResult.setEventCount(10L);
                    rebuildResult.setExposureCount(4L);
                    rebuildResult.setMabRewardCount(6L);
                    return rebuildResult;
                });

        EventPipelineOperationResponse response = analysisService.replayEventPipeline("exp_replay", "tester");

        assertThat(response.getStatus()).isEqualTo("RUNNING");
        verify(replayJobRepository).updateProgress(eq(response.getReplayJobId()), eq(1L), eq(1L), eq(0L),
                eq(1L), eq(0L));
        verify(replayJobRepository).markSucceeded(eq(response.getReplayJobId()), eq(14L), eq(10L), eq(4L),
                eq(2L), eq(6L), any());
        verify(replayJobRepository).markCancelled(eq(response.getReplayJobId()),
                org.mockito.ArgumentMatchers.contains("派生数据已完成一致性重建"), any());
        verify(replayJobRepository, never()).markFailed(anyString(), anyString(), any());
    }

    @Test
    void replayEventPipelineShouldCreateFilteredCopyReplayJob() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayMaxFilteredCopyFacts", 10L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setEventTypes(List.of("pay_success"));
        request.setIncludeExposures(false);
        EventPipelineRebuildResult replayResult = new EventPipelineRebuildResult();
        replayResult.setGroupCount(1L);
        replayResult.setEventCount(3L);
        replayResult.setExposureCount(0L);
        replayResult.setMabRewardCount(2L);
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(true);
        when(eventRepository.countByReplayScope("exp_replay", "A", null, null, List.of("PAY_SUCCESS")))
                .thenReturn(3L);
        when(eventInboxMaterializer.copyReplayDerivedData(eq("exp_replay"), isNull(), isNull(),
                eq(List.of("PAY_SUCCESS")), eq(true), eq(false), anyString(),
                any(EventReplayProgressReporter.class))).thenReturn(replayResult);

        EventPipelineOperationResponse response = analysisService.replayEventPipeline("exp_replay", request,
                "tester");

        assertThat(response.getOperation()).isEqualTo("REPLAY_DERIVED");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getReplayJobId()).startsWith("replay_");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_RUNNING);
        assertThat(response.getReplayMode()).isEqualTo("FILTERED_DERIVED_COPY_REPLAY");
        assertThat(response.getFullDerivedReplay()).isFalse();
        assertThat(response.getEventTypes()).containsExactly("PAY_SUCCESS");
        assertThat(response.getIncludeEvents()).isTrue();
        assertThat(response.getIncludeExposures()).isFalse();
        verify(replayJobRepository).createRunningJob(argThat(record ->
                "FILTERED_DERIVED_COPY_REPLAY".equals(record.getReplayMode())
                        && Boolean.FALSE.equals(record.getFullDerivedReplay())
                        && Boolean.TRUE.equals(record.getIncludeEvents())
                        && Boolean.FALSE.equals(record.getIncludeExposures())
                        && List.of("PAY_SUCCESS").equals(record.getEventTypes())
                        && Long.valueOf(3L).equals(record.getPlannedAffectedCount())
                        && Long.valueOf(3L).equals(record.getPlannedEventCount())
                        && Long.valueOf(0L).equals(record.getPlannedExposureCount())
                        && Long.valueOf(1L).equals(record.getPlannedGroupCount())));
        verify(replayJobRepository).markSucceeded(eq(response.getReplayJobId()), eq(3L), eq(3L), eq(0L),
                eq(1L), eq(2L), any());
        verify(eventInboxMaterializer).copyReplayDerivedData(eq("exp_replay"), isNull(), isNull(),
                eq(List.of("PAY_SUCCESS")), eq(true), eq(false), eq(response.getReplayJobId()),
                any(EventReplayProgressReporter.class));
        verify(eventInboxMaterializer, never()).rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class));
    }

    @Test
    void replayEventPipelineShouldRejectFilteredCopyReplayWhenPlanExceedsLimit() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayMaxFilteredCopyFacts", 2L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_replay", "A", null, null,
                List.of("PAY_SUCCESS"))).thenReturn(3L);
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setEventTypes(List.of("PAY_SUCCESS"));
        request.setIncludeExposures(false);

        assertThatThrownBy(() -> analysisService.replayEventPipeline("exp_replay", request, "tester"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.BAD_REQUEST))
                .hasMessageContaining("影响事实数超过上限")
                .hasMessageContaining("maxFilteredCopyFacts=2");
        verify(replayJobRepository, never()).createRunningJob(any(EventReplayJobRecord.class));
        verify(eventInboxMaterializer, never()).copyReplayDerivedData(eq("exp_replay"), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyString(), any(EventReplayProgressReporter.class));
        verify(eventInboxMaterializer, never()).rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class));
    }

    @Test
    void replayEventPipelineShouldRejectConcurrentReplayJob() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(false);

        assertThatThrownBy(() -> analysisService.replayEventPipeline("exp_replay", "tester"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT))
                .hasMessageContaining("事件重放任务正在运行");
        verify(eventInboxMaterializer, never()).rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class));
        verify(replayJobRepository, never()).markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any());
    }

    @Test
    void replayEventPipelineShouldMarkReplayJobFailedWhenRebuildFails() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(eventInboxMaterializer.rebuildDerivedData(eq("exp_replay"), anyString(),
                any(EventReplayProgressReporter.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        EventPipelineOperationResponse response = analysisService.replayEventPipeline("exp_replay", "tester");

        assertThat(response.getOperation()).isEqualTo("REPLAY_DERIVED");
        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_RUNNING);
        verify(replayJobRepository).markFailed(anyString(), eq("redis unavailable"), any());
    }

    @Test
    void listEventReplayJobsShouldReturnRecentReplayJobResponses() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime finishedAt = startedAt.plusSeconds(3);
        EventReplayJobRecord record = new EventReplayJobRecord();
        record.setReplayJobId("replay_1");
        record.setExperimentId("exp_replay");
        record.setOperator("tester");
        record.setJobStatus(EventReplayJobRecord.STATUS_SUCCEEDED);
        record.setReplayMode("FILTERED_DERIVED_COPY_REPLAY");
        record.setScopeStartTime(startedAt.minusHours(2));
        record.setScopeEndTime(startedAt.minusHours(1));
        record.setEventTypes(List.of("PAY_SUCCESS"));
        record.setIncludeEvents(true);
        record.setIncludeExposures(false);
        record.setFullDerivedReplay(false);
        record.setAffectedCount(14L);
        record.setEventCount(10L);
        record.setExposureCount(4L);
        record.setGroupCount(2L);
        record.setMabRewardCount(6L);
        record.setStartedAt(startedAt);
        record.setFinishedAt(finishedAt);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.listRecentByExperimentId("exp_replay", 50)).thenReturn(List.of(record));

        List<EventReplayJobResponse> responses = analysisService.listEventReplayJobs("exp_replay", 99);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getReplayJobId()).isEqualTo("replay_1");
        assertThat(responses.get(0).getJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_SUCCEEDED);
        assertThat(responses.get(0).getReplayMode()).isEqualTo("FILTERED_DERIVED_COPY_REPLAY");
        assertThat(responses.get(0).getScopeStartTime()).isEqualTo(startedAt.minusHours(2));
        assertThat(responses.get(0).getScopeEndTime()).isEqualTo(startedAt.minusHours(1));
        assertThat(responses.get(0).getEventTypes()).containsExactly("PAY_SUCCESS");
        assertThat(responses.get(0).getIncludeEvents()).isTrue();
        assertThat(responses.get(0).getIncludeExposures()).isFalse();
        assertThat(responses.get(0).getFullDerivedReplay()).isFalse();
        assertThat(responses.get(0).getAffectedCount()).isEqualTo(14L);
        assertThat(responses.get(0).getMabRewardCount()).isEqualTo(6L);
        assertThat(responses.get(0).getStartedAt()).isEqualTo(startedAt);
        assertThat(responses.get(0).getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void getEventReplayJobShouldReturnReplayJobDetail() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        EventReplayJobRecord record = runningReplayJobRecord("replay_detail", "exp_replay");
        record.setPlannedAffectedCount(100L);
        record.setPlannedEventCount(80L);
        record.setPlannedExposureCount(20L);
        record.setPlannedGroupCount(2L);
        record.setAffectedCount(25L);
        record.setEventCount(20L);
        record.setExposureCount(5L);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.findByExperimentIdAndReplayJobId("exp_replay", "replay_detail"))
                .thenReturn(record);

        EventReplayJobResponse response = analysisService.getEventReplayJob("exp_replay", "replay_detail");

        assertThat(response.getReplayJobId()).isEqualTo("replay_detail");
        assertThat(response.getJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_RUNNING);
        assertThat(response.getReplayMode()).isEqualTo("FULL_DERIVED_REBUILD");
        assertThat(response.getFullDerivedReplay()).isTrue();
        assertThat(response.getPlannedAffectedCount()).isEqualTo(100L);
        assertThat(response.getPlannedEventCount()).isEqualTo(80L);
        assertThat(response.getPlannedExposureCount()).isEqualTo(20L);
        assertThat(response.getPlannedGroupCount()).isEqualTo(2L);
        assertThat(response.getAffectedCount()).isEqualTo(25L);
        assertThat(response.getProgressPercent()).isEqualTo(25);
    }

    @Test
    void cancelEventReplayJobShouldRequestCancellationForRunningJob() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        EventReplayJobRecord record = runningReplayJobRecord("replay_cancel", "exp_replay");
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.findByExperimentIdAndReplayJobId("exp_replay", "replay_cancel"))
                .thenReturn(record);
        when(replayJobRepository.requestCancellation(eq("replay_cancel"), anyString())).thenReturn(true);

        EventPipelineOperationResponse response =
                analysisService.cancelEventReplayJob("exp_replay", "replay_cancel", "operator-a");

        assertThat(response.getOperation()).isEqualTo("CANCEL_REPLAY_JOB");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getReplayJobId()).isEqualTo("replay_cancel");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
        assertThat(response.getReplayMode()).isEqualTo("FULL_DERIVED_REBUILD");
        verify(replayJobRepository).requestCancellation(eq("replay_cancel"),
                org.mockito.ArgumentMatchers.contains("operator=operator-a"));
        verify(replayJobRepository, never()).markCancelled(anyString(), anyString(), any());
    }

    @Test
    void cancelEventReplayJobShouldTreatCancelRequestedJobAsIdempotent() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        EventReplayJobRecord record = runningReplayJobRecord("replay_cancel", "exp_replay");
        record.setJobStatus(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.findByExperimentIdAndReplayJobId("exp_replay", "replay_cancel"))
                .thenReturn(record);

        EventPipelineOperationResponse response =
                analysisService.cancelEventReplayJob("exp_replay", "replay_cancel", "operator-a");

        assertThat(response.getOperation()).isEqualTo("CANCEL_REPLAY_JOB");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_CANCEL_REQUESTED);
        assertThat(response.getMessage()).contains("取消已在处理中");
        verify(replayJobRepository, never()).requestCancellation(anyString(), anyString());
        verify(replayJobRepository, never()).markCancelled(anyString(), anyString(), any());
    }

    @Test
    void cancelEventReplayJobShouldRejectTerminalJob() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        EventReplayJobRecord record = runningReplayJobRecord("replay_done", "exp_replay");
        record.setJobStatus(EventReplayJobRecord.STATUS_SUCCEEDED);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(new ExperimentMetadata());
        when(replayJobRepository.findByExperimentIdAndReplayJobId("exp_replay", "replay_done"))
                .thenReturn(record);

        assertThatThrownBy(() -> analysisService.cancelEventReplayJob("exp_replay", "replay_done", "tester"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.CONFLICT))
                .hasMessageContaining("只有运行中的事件重放任务可以取消");
        verify(replayJobRepository, never()).requestCancellation(anyString(), anyString());
        verify(replayJobRepository, never()).markCancelled(anyString(), anyString(), any());
    }

    @Test
    void planEventReplayShouldReturnFilteredFactSelectionWithoutMutatingDerivedData() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        LocalDateTime startTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime endTime = startTime.plusHours(2);
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        request.setEventTypes(List.of("pay_success", " ", "PAY_SUCCESS"));
        request.setIncludeEvents(true);
        request.setIncludeExposures(false);

        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_replay", "A", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(3L);
        when(eventRepository.countByReplayScope("exp_replay", "B", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(5L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "A", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(2L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "B", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(4L);

        EventReplayPlanResponse response = analysisService.planEventReplay("exp_replay", request);

        assertThat(response.getExperimentId()).isEqualTo("exp_replay");
        assertThat(response.getReplayMode()).isEqualTo("FILTERED_DERIVED_COPY_REPLAY");
        assertThat(response.getFullDerivedReplay()).isFalse();
        assertThat(response.getEventTypes()).containsExactly("PAY_SUCCESS");
        assertThat(response.getIncludeEvents()).isTrue();
        assertThat(response.getIncludeExposures()).isFalse();
        assertThat(response.getGroupCount()).isEqualTo(2L);
        assertThat(response.getEventCount()).isEqualTo(8L);
        assertThat(response.getMaterializedEventCount()).isEqualTo(6L);
        assertThat(response.getUnmaterializedEventCount()).isEqualTo(2L);
        assertThat(response.getExposureCount()).isZero();
        assertThat(response.getMaterializedExposureCount()).isZero();
        assertThat(response.getUnmaterializedExposureCount()).isZero();
        assertThat(response.getAffectedCount()).isEqualTo(8L);
        assertThat(response.getMaterializedCount()).isEqualTo(6L);
        assertThat(response.getUnmaterializedCount()).isEqualTo(2L);
        assertThat(response.getMessage()).contains("复制型 replay").contains("不会清空 Redis/MAB 派生数据");
        assertThat(response.getGroups())
                .extracting(EventReplayPlanResponse.GroupReplayPlan::getGroupId,
                        EventReplayPlanResponse.GroupReplayPlan::getGroupName,
                        EventReplayPlanResponse.GroupReplayPlan::getEventCount,
                        EventReplayPlanResponse.GroupReplayPlan::getMaterializedEventCount,
                        EventReplayPlanResponse.GroupReplayPlan::getUnmaterializedEventCount,
                        EventReplayPlanResponse.GroupReplayPlan::getAffectedCount,
                        EventReplayPlanResponse.GroupReplayPlan::getMaterializedCount,
                        EventReplayPlanResponse.GroupReplayPlan::getUnmaterializedCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", "基准组", 3L, 2L, 1L, 3L, 2L, 1L),
                        org.assertj.core.groups.Tuple.tuple("B", "实验组", 5L, 4L, 1L, 5L, 4L, 1L));
        verify(exposureRepository, never()).countByReplayScope(anyString(), anyString(), any(), any());
        verify(materializationRepository, never()).countMaterializedExposuresByReplayScope(anyString(), anyString(),
                any(), any());
    }

    @Test
    void planEventReplayShouldMarkEmptyRequestAsFullDerivedReplayPlan() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_replay", "A", null, null, List.of())).thenReturn(10L);
        when(eventRepository.countByReplayScope("exp_replay", "B", null, null, List.of())).thenReturn(12L);
        when(exposureRepository.countByReplayScope("exp_replay", "A", null, null)).thenReturn(4L);
        when(exposureRepository.countByReplayScope("exp_replay", "B", null, null)).thenReturn(6L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "A", null, null,
                List.of())).thenReturn(7L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "B", null, null,
                List.of())).thenReturn(10L);
        when(materializationRepository.countMaterializedExposuresByReplayScope("exp_replay", "A", null,
                null)).thenReturn(3L);
        when(materializationRepository.countMaterializedExposuresByReplayScope("exp_replay", "B", null,
                null)).thenReturn(5L);

        EventReplayPlanResponse response = analysisService.planEventReplay("exp_replay", null);

        assertThat(response.getReplayMode()).isEqualTo("FULL_DERIVED_REBUILD");
        assertThat(response.getFullDerivedReplay()).isTrue();
        assertThat(response.getEventCount()).isEqualTo(22L);
        assertThat(response.getMaterializedEventCount()).isEqualTo(17L);
        assertThat(response.getUnmaterializedEventCount()).isEqualTo(5L);
        assertThat(response.getExposureCount()).isEqualTo(10L);
        assertThat(response.getMaterializedExposureCount()).isEqualTo(8L);
        assertThat(response.getUnmaterializedExposureCount()).isEqualTo(2L);
        assertThat(response.getAffectedCount()).isEqualTo(32L);
        assertThat(response.getMaterializedCount()).isEqualTo(25L);
        assertThat(response.getUnmaterializedCount()).isEqualTo(7L);
        assertThat(response.getMessage()).contains("/events/replay");
    }

    @Test
    void repairEventMaterializationShouldNoopWhenPlanHasNoGaps() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        when(configService.getExperimentConfig("exp_repair")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_repair", "A", null, null, List.of())).thenReturn(3L);
        when(exposureRepository.countByReplayScope("exp_repair", "A", null, null)).thenReturn(2L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_repair", "A", null, null,
                List.of())).thenReturn(3L);
        when(materializationRepository.countMaterializedExposuresByReplayScope("exp_repair", "A", null,
                null)).thenReturn(2L);

        EventPipelineOperationResponse response = analysisService.repairEventMaterialization("exp_repair", null,
                "tester");

        assertThat(response.getOperation()).isEqualTo("REPAIR_MATERIALIZATION");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAffectedCount()).isZero();
        assertThat(response.getMessage()).contains("无需修复");
        verify(replayJobRepository, never()).createRunningJob(any(EventReplayJobRecord.class));
    }

    @Test
    void repairEventMaterializationShouldRunFullReplayWhenFullPlanHasGaps() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        EventPipelineRebuildResult rebuildResult = new EventPipelineRebuildResult();
        rebuildResult.setGroupCount(2L);
        rebuildResult.setEventCount(10L);
        rebuildResult.setExposureCount(4L);
        rebuildResult.setMabRewardCount(6L);
        when(configService.getExperimentConfig("exp_repair")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_repair", "A", null, null, List.of())).thenReturn(10L);
        when(exposureRepository.countByReplayScope("exp_repair", "A", null, null)).thenReturn(4L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_repair", "A", null, null,
                List.of())).thenReturn(8L);
        when(materializationRepository.countMaterializedExposuresByReplayScope("exp_repair", "A", null,
                null)).thenReturn(4L);
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(true);
        when(eventInboxMaterializer.rebuildDerivedData(eq("exp_repair"), anyString(),
                any(EventReplayProgressReporter.class))).thenReturn(rebuildResult);

        EventPipelineOperationResponse response = analysisService.repairEventMaterialization("exp_repair", null,
                "tester");

        assertThat(response.getOperation()).isEqualTo("REPAIR_MATERIALIZATION");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAffectedCount()).isEqualTo(14L);
        assertThat(response.getReplayJobId()).startsWith("replay_");
        assertThat(response.getMessage()).contains("unmaterializedCount=2");
        verify(eventInboxMaterializer).rebuildDerivedData(eq("exp_repair"), eq(response.getReplayJobId()),
                any(EventReplayProgressReporter.class));
    }

    @Test
    void repairEventMaterializationShouldRunLocalRepairWhenFilteredPlanHasGaps() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);
        when(configService.getExperimentConfig("exp_repair")).thenReturn(metadataWithGroups());
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setIncludeEvents(true);
        request.setIncludeExposures(false);
        request.setEventTypes(List.of("PAY_SUCCESS"));
        when(eventRepository.countByReplayScope("exp_repair", "A", null, null,
                List.of("PAY_SUCCESS"))).thenReturn(3L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_repair", "A", null, null,
                List.of("PAY_SUCCESS"))).thenReturn(1L);
        EventPipelineRebuildResult repairResult = new EventPipelineRebuildResult();
        repairResult.setEventCount(2L);
        repairResult.setExposureCount(0L);
        repairResult.setGroupCount(1L);
        repairResult.setMabRewardCount(1L);
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(true);
        when(eventInboxMaterializer.repairUnmaterializedDerivedData(eq("exp_repair"), isNull(), isNull(),
                eq(List.of("PAY_SUCCESS")), eq(true), eq(false), anyString(),
                any(EventReplayProgressReporter.class))).thenReturn(repairResult);

        EventPipelineOperationResponse response = analysisService.repairEventMaterialization("exp_repair", request,
                "tester");

        assertThat(response.getOperation()).isEqualTo("REPAIR_MATERIALIZATION");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAffectedCount()).isEqualTo(2L);
        assertThat(response.getEventCount()).isEqualTo(2L);
        assertThat(response.getExposureCount()).isZero();
        assertThat(response.getGroupCount()).isEqualTo(1L);
        assertThat(response.getMabRewardCount()).isEqualTo(1L);
        assertThat(response.getReplayJobId()).startsWith("replay_");
        assertThat(response.getReplayJobStatus()).isEqualTo(EventReplayJobRecord.STATUS_SUCCEEDED);
        assertThat(response.getReplayMode()).isEqualTo("FILTERED_DERIVED_COPY_REPLAY");
        assertThat(response.getFullDerivedReplay()).isFalse();
        assertThat(response.getIncludeEvents()).isTrue();
        assertThat(response.getIncludeExposures()).isFalse();
        assertThat(response.getEventTypes()).containsExactly("PAY_SUCCESS");
        assertThat(response.getMessage()).contains("局部补物化").contains("广义派生漂移仍需全量重放");
        verify(replayJobRepository).createRunningJob(argThat(record ->
                "FILTERED_DERIVED_COPY_REPLAY".equals(record.getReplayMode())
                        && Boolean.FALSE.equals(record.getFullDerivedReplay())
                        && Boolean.TRUE.equals(record.getIncludeEvents())
                        && Boolean.FALSE.equals(record.getIncludeExposures())
                        && List.of("PAY_SUCCESS").equals(record.getEventTypes())
                        && Long.valueOf(2L).equals(record.getPlannedAffectedCount())
                        && Long.valueOf(2L).equals(record.getPlannedEventCount())
                        && Long.valueOf(0L).equals(record.getPlannedExposureCount())
                        && Long.valueOf(1L).equals(record.getPlannedGroupCount())));
        verify(eventInboxMaterializer).repairUnmaterializedDerivedData(eq("exp_repair"), isNull(), isNull(),
                eq(List.of("PAY_SUCCESS")), eq(true), eq(false), eq(response.getReplayJobId()),
                any(EventReplayProgressReporter.class));
        verify(eventInboxMaterializer, never()).rebuildDerivedData(eq("exp_repair"), anyString(),
                any(EventReplayProgressReporter.class));
        verify(exposureRepository, never()).countByReplayScope(anyString(), anyString(), any(), any());
    }

    @Test
    void planEventReplayShouldReturnSegmentPlanWhenSegmentCountRequested() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);

        LocalDateTime startTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 30, 12, 0);
        LocalDateTime firstSegmentEndTime = LocalDateTime.of(2026, 7, 30, 11, 0);
        LocalDateTime secondSegmentStartTime = firstSegmentEndTime.plusNanos(1L);
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        request.setEventTypes(List.of("pay_success"));
        request.setIncludeEvents(true);
        request.setIncludeExposures(false);
        request.setSegmentCount(2);

        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope("exp_replay", "A", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(6L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "A", startTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(4L);
        when(eventRepository.countByReplayScope("exp_replay", "A", startTime, firstSegmentEndTime,
                List.of("PAY_SUCCESS"))).thenReturn(2L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "A", startTime,
                firstSegmentEndTime, List.of("PAY_SUCCESS"))).thenReturn(2L);
        when(eventRepository.countByReplayScope("exp_replay", "A", secondSegmentStartTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(4L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_replay", "A",
                secondSegmentStartTime, endTime, List.of("PAY_SUCCESS"))).thenReturn(2L);

        EventReplayPlanResponse response = analysisService.planEventReplay("exp_replay", request);

        assertThat(response.getRequestedSegmentCount()).isEqualTo(2);
        assertThat(response.getSegmentCount()).isEqualTo(2);
        assertThat(response.getSegmentRecoverySupported()).isTrue();
        assertThat(response.getMaxSegmentAffectedCount()).isEqualTo(4L);
        assertThat(response.getMaxSegmentUnmaterializedCount()).isEqualTo(2L);
        assertThat(response.getSegments()).hasSize(2);
        assertThat(response.getSegments().get(0).getSegmentIndex()).isZero();
        assertThat(response.getSegments().get(0).getStartTime()).isEqualTo(startTime);
        assertThat(response.getSegments().get(0).getEndTime()).isEqualTo(firstSegmentEndTime);
        assertThat(response.getSegments().get(0).getUnmaterializedCount()).isZero();
        assertThat(response.getSegments().get(0).getRecommendedAction()).isEqualTo("NONE");
        assertThat(response.getSegments().get(1).getSegmentIndex()).isEqualTo(1);
        assertThat(response.getSegments().get(1).getStartTime()).isEqualTo(secondSegmentStartTime);
        assertThat(response.getSegments().get(1).getEndTime()).isEqualTo(endTime);
        assertThat(response.getSegments().get(1).getAffectedCount()).isEqualTo(4L);
        assertThat(response.getSegments().get(1).getUnmaterializedCount()).isEqualTo(2L);
        assertThat(response.getSegments().get(1).getRecommendedAction())
                .isEqualTo("REPAIR_MATERIALIZATION_SEGMENT");
        verify(exposureRepository, never()).countByReplayScope(anyString(), anyString(), any(), any());
    }

    @Test
    void repairEventMaterializationSegmentShouldUseResolvedSegmentScope() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        EventReplayJobRepository replayJobRepository = mock(EventReplayJobRepository.class);
        EventInboxMaterializer eventInboxMaterializer = mock(EventInboxMaterializer.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        ReflectionTestUtils.setField(analysisService, "eventReplayJobRepository", replayJobRepository);
        ReflectionTestUtils.setField(analysisService, "eventInboxMaterializer", eventInboxMaterializer);
        ReflectionTestUtils.setField(analysisService, "eventPipelineReplayJobTimeoutMinutes", 30L);

        LocalDateTime startTime = LocalDateTime.of(2026, 7, 30, 10, 0);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 30, 12, 0);
        LocalDateTime firstSegmentEndTime = LocalDateTime.of(2026, 7, 30, 11, 0);
        LocalDateTime secondSegmentStartTime = firstSegmentEndTime.plusNanos(1L);
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        request.setEventTypes(List.of("PAY_SUCCESS"));
        request.setIncludeEvents(true);
        request.setIncludeExposures(false);
        request.setSegmentCount(2);

        when(configService.getExperimentConfig("exp_repair")).thenReturn(metadataWithGroups());
        when(eventRepository.countByReplayScope(eq("exp_repair"), eq("A"), any(), any(),
                eq(List.of("PAY_SUCCESS")))).thenReturn(6L);
        when(materializationRepository.countMaterializedEventsByReplayScope(eq("exp_repair"), eq("A"), any(), any(),
                eq(List.of("PAY_SUCCESS")))).thenReturn(4L);
        when(eventRepository.countByReplayScope("exp_repair", "A", secondSegmentStartTime, endTime,
                List.of("PAY_SUCCESS"))).thenReturn(4L);
        when(materializationRepository.countMaterializedEventsByReplayScope("exp_repair", "A",
                secondSegmentStartTime, endTime, List.of("PAY_SUCCESS"))).thenReturn(2L);
        EventPipelineRebuildResult repairResult = new EventPipelineRebuildResult();
        repairResult.setEventCount(2L);
        repairResult.setGroupCount(1L);
        when(replayJobRepository.createRunningJob(any(EventReplayJobRecord.class))).thenReturn(true);
        when(replayJobRepository.markSucceeded(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                any())).thenReturn(true);
        when(eventInboxMaterializer.repairUnmaterializedDerivedData(eq("exp_repair"), eq(secondSegmentStartTime),
                eq(endTime), eq(List.of("PAY_SUCCESS")), eq(true), eq(false), anyString(),
                any(EventReplayProgressReporter.class))).thenReturn(repairResult);

        EventPipelineOperationResponse response =
                analysisService.repairEventMaterializationSegment("exp_repair", request, 1, "tester");

        assertThat(response.getOperation()).isEqualTo("REPAIR_MATERIALIZATION");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getAffectedCount()).isEqualTo(2L);
        assertThat(response.getReplayMode()).isEqualTo("FILTERED_DERIVED_COPY_REPLAY");
        assertThat(response.getScopeStartTime()).isEqualTo(secondSegmentStartTime);
        assertThat(response.getScopeEndTime()).isEqualTo(endTime);
        assertThat(response.getMessage()).contains("segmentIndex=1");
        verify(replayJobRepository).createRunningJob(argThat(record ->
                secondSegmentStartTime.equals(record.getScopeStartTime())
                        && endTime.equals(record.getScopeEndTime())
                        && Long.valueOf(2L).equals(record.getPlannedAffectedCount())
                        && Long.valueOf(2L).equals(record.getPlannedEventCount())
                        && Long.valueOf(0L).equals(record.getPlannedExposureCount())
                        && Long.valueOf(1L).equals(record.getPlannedGroupCount())));
        verify(eventInboxMaterializer).repairUnmaterializedDerivedData(eq("exp_repair"), eq(secondSegmentStartTime),
                eq(endTime), eq(List.of("PAY_SUCCESS")), eq(true), eq(false), eq(response.getReplayJobId()),
                any(EventReplayProgressReporter.class));
        verify(exposureRepository, never()).countByReplayScope(anyString(), anyString(), any(), any());
    }

    @Test
    void repairEventMaterializationSegmentShouldRejectPlanWithoutSegments() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ExperimentEventRepository eventRepository = mock(ExperimentEventRepository.class);
        ExperimentExposureRepository exposureRepository = mock(ExperimentExposureRepository.class);
        EventMaterializationRepository materializationRepository = mock(EventMaterializationRepository.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        ReflectionTestUtils.setField(analysisService, "experimentEventRepository", eventRepository);
        ReflectionTestUtils.setField(analysisService, "experimentExposureRepository", exposureRepository);
        ReflectionTestUtils.setField(analysisService, "eventMaterializationRepository", materializationRepository);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());

        assertThatThrownBy(() -> analysisService.repairEventMaterializationSegment("exp_replay", null, 0,
                "tester"))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.BAD_REQUEST))
                .hasMessageContaining("不支持分段恢复");
    }

    @Test
    void planEventReplayShouldRejectInvalidScope() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setIncludeEvents(false);
        request.setIncludeExposures(false);

        assertThatThrownBy(() -> analysisService.planEventReplay("exp_replay", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.BAD_REQUEST));
    }

    @Test
    void planEventReplayShouldRejectInvertedTimeRange() {
        AnalysisServiceImpl analysisService = new AnalysisServiceImpl();
        ConfigService configService = mock(ConfigService.class);
        ReflectionTestUtils.setField(analysisService, "configService", configService);
        when(configService.getExperimentConfig("exp_replay")).thenReturn(metadataWithGroups());
        EventReplayPlanRequest request = new EventReplayPlanRequest();
        request.setStartTime(LocalDateTime.of(2026, 7, 30, 12, 0));
        request.setEndTime(LocalDateTime.of(2026, 7, 30, 10, 0));

        assertThatThrownBy(() -> analysisService.planEventReplay("exp_replay", request))
                .isInstanceOf(BusinessException.class)
                .satisfies(throwable -> assertThat(((BusinessException) throwable).getResponseCode())
                        .isEqualTo(ResponseCode.BAD_REQUEST));
    }

    private ExperimentMetadata metadataWithGroups() {
        ExperimentMetadata metadata = new ExperimentMetadata();
        Map<String, ExperimentGroup> groups = new LinkedHashMap<>();
        groups.put("A", group("A", "基准组"));
        groups.put("B", group("B", "实验组"));
        metadata.setGroups(groups);
        return metadata;
    }

    private ExperimentGroup group(String groupId, String groupName) {
        ExperimentGroup group = new ExperimentGroup();
        group.setId(groupId);
        group.setName(groupName);
        return group;
    }

    private EventReplayJobRecord runningReplayJobRecord(String replayJobId, String experimentId) {
        EventReplayJobRecord record = new EventReplayJobRecord();
        record.setReplayJobId(replayJobId);
        record.setExperimentId(experimentId);
        record.setOperator("tester");
        record.setJobStatus(EventReplayJobRecord.STATUS_RUNNING);
        record.setActiveKey(experimentId);
        record.setReplayMode("FULL_DERIVED_REBUILD");
        record.setEventTypes(List.of());
        record.setIncludeEvents(true);
        record.setIncludeExposures(true);
        record.setFullDerivedReplay(true);
        record.setAffectedCount(0L);
        record.setEventCount(0L);
        record.setExposureCount(0L);
        record.setGroupCount(0L);
        record.setMabRewardCount(0L);
        record.setStartedAt(LocalDateTime.of(2026, 7, 30, 10, 0));
        return record;
    }

    private List<EventInboxStatusCountEntity> statusCounts(Object... values) {
        List<EventInboxStatusCountEntity> statusCounts = new java.util.ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            EventInboxStatusCountEntity statusCount = new EventInboxStatusCountEntity();
            statusCount.setStatus(String.valueOf(values[index]));
            statusCount.setEventCount((Long) values[index + 1]);
            statusCounts.add(statusCount);
        }
        return statusCounts;
    }
}
