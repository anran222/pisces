package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentEventFact;
import com.pisces.common.model.ExperimentExposure;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.MetricDefinition;
import com.pisces.service.event.EventMaterializationRecord;
import com.pisces.service.event.EventInboxConstants;
import com.pisces.service.event.EventInboxRecord;
import com.pisces.service.repository.EventMaterializationRepository;
import com.pisces.service.repository.ExperimentEventRepository;
import com.pisces.service.repository.ExperimentExposureRepository;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.MultiArmedBanditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventInboxMaterializerTest {

    @Mock
    private ExperimentEventRepository experimentEventRepository;

    @Mock
    private ExperimentExposureRepository experimentExposureRepository;

    @Mock
    private EventMaterializationRepository eventMaterializationRepository;

    @Mock
    private ConfigService configService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private MultiArmedBanditService mabService;

    private EventInboxMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new EventInboxMaterializer(
                experimentEventRepository,
                experimentExposureRepository,
                eventMaterializationRepository,
                configService,
                redisTemplate);
        ReflectionTestUtils.setField(materializer, "mabService", mabService);
        ReflectionTestUtils.setField(materializer, "eventReplayBatchSize", 1000);
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void materializeShouldRecordFailureForPrimaryMetricDenominatorEvent() {
        String experimentId = "exp_event_mab";
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(true);
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        EventInboxRecord record = eventRecord(experimentId, "PRODUCT_VIEW", Map.of());

        materializer.materialize(record);

        verify(mabService).recordRewardObservation(
                eq(experimentId), eq("A"), eq("visitor-1"), eq(false));
    }

    @Test
    void materializeShouldRecordSuccessForPrimaryMetricNumeratorEvent() {
        String experimentId = "exp_event_mab";
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(true);
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        EventInboxRecord record = eventRecord(experimentId, "PAY_SUCCESS", Map.of());

        materializer.materialize(record);

        verify(mabService).recordRewardObservation(
                eq(experimentId), eq("A"), eq("visitor-1"), eq(true));
    }

    @Test
    void materializeShouldUseExplicitMabObservationIdWhenPresent() {
        String experimentId = "exp_event_mab";
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(true);
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        EventInboxRecord record = eventRecord(experimentId, "PAY_SUCCESS",
                Map.of("mabObservationId", "checkout-001"));

        materializer.materialize(record);

        verify(mabService).recordRewardObservation(
                eq(experimentId), eq("A"), eq("checkout-001"), eq(true));
    }

    @Test
    void materializeShouldRecoverDerivativesWhenFactExistsWithoutMaterializationRecord() {
        String experimentId = "exp_event_mab";
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(false);
        when(eventMaterializationRepository.exists(EventMaterializationRecord.FACT_KIND_EVENT,
                "evt_product_view")).thenReturn(false);
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        EventInboxRecord record = eventRecord(experimentId, "PRODUCT_VIEW", Map.of());

        materializer.materialize(record);

        verify(listOperations).rightPush(eq("pisces:event:store:" + experimentId + ":A"), any());
        verify(hashOperations).increment("pisces:event:counter:" + experimentId + ":A", "PRODUCT_VIEW", 1);
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_product_view".equals(materialization.getFactId())
                        && experimentId.equals(materialization.getExperimentId())
                        && "A".equals(materialization.getGroupId())
                        && "PRODUCT_VIEW".equals(materialization.getEventType())
                        && EventMaterializationRecord.SOURCE_INBOX.equals(
                                materialization.getMaterializationSource())));
    }

    @Test
    void materializeShouldUsePersistedEventFactIdWhenDuplicateClientEventExists() {
        String experimentId = "exp_event_mab";
        ExperimentEventFact persistedFact = eventFact("evt_existing_payment", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success");
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(false);
        when(experimentEventRepository.findByExperimentIdAndClientEventId(experimentId, "client-pay_success"))
                .thenReturn(persistedFact);
        EventInboxRecord record = eventRecord(experimentId, "PAY_SUCCESS", Map.of());

        materializer.materialize(record);

        verify(eventMaterializationRepository).exists(EventMaterializationRecord.FACT_KIND_EVENT,
                "evt_existing_payment");
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_existing_payment".equals(materialization.getFactId())
                        && experimentId.equals(materialization.getExperimentId())
                        && "A".equals(materialization.getGroupId())
                        && "PAY_SUCCESS".equals(materialization.getEventType())));
    }

    @Test
    void materializeShouldSkipDerivativesWhenFactAndMaterializationRecordExist() {
        String experimentId = "exp_event_mab";
        when(experimentEventRepository.saveIfAbsent(any())).thenReturn(false);
        when(eventMaterializationRepository.exists(EventMaterializationRecord.FACT_KIND_EVENT,
                "evt_product_view")).thenReturn(true);
        EventInboxRecord record = eventRecord(experimentId, "PRODUCT_VIEW", Map.of());

        materializer.materialize(record);

        verify(listOperations, never()).rightPush(anyString(), any());
        verify(hashOperations, never()).increment(anyString(), any(), eq(1L));
        verify(eventMaterializationRepository, never()).saveOrRefresh(any());
    }

    @Test
    void materializeShouldUsePersistedExposureIdWhenDuplicateExposureExists() {
        String experimentId = "exp_event_mab";
        ExperimentExposure persistedExposure = exposure("expo_existing_home", experimentId, "A",
                "visitor-1", "exp_event_mab:visitor-1:A");
        when(experimentExposureRepository.saveIfAbsent(any())).thenReturn(false);
        when(experimentExposureRepository.findByIdempotencyKey("exp_event_mab:visitor-1:A"))
                .thenReturn(persistedExposure);
        EventInboxRecord record = exposureRecord(experimentId);

        materializer.materialize(record);

        verify(eventMaterializationRepository).exists(EventMaterializationRecord.FACT_KIND_EXPOSURE,
                "expo_existing_home");
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EXPOSURE.equals(materialization.getFactKind())
                        && "expo_existing_home".equals(materialization.getFactId())
                        && experimentId.equals(materialization.getExperimentId())
                        && "A".equals(materialization.getGroupId())));
    }

    @Test
    void repairUnmaterializedDerivedDataShouldMaterializeMissingScopedFacts() {
        String experimentId = "exp_event_mab";
        ExperimentEventFact eventFact = eventFact("evt_repair_payment", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success");
        ExperimentExposure exposure = exposure("expo_repair_home", experimentId, "A",
                "visitor-1", "exp_event_mab:visitor-1:A");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listUnmaterializedByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1000))).thenReturn(List.of(eventFact));
        when(experimentExposureRepository.listUnmaterializedByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(0L), eq(1000))).thenReturn(List.of(exposure));
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());
        when(mabService.recordRewardObservation(experimentId, "A", "visitor-1", true)).thenReturn(true);

        var result = materializer.repairUnmaterializedDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, true, "replay_local");

        assertThat(result.getEventCount()).isEqualTo(1L);
        assertThat(result.getExposureCount()).isEqualTo(1L);
        assertThat(result.getGroupCount()).isEqualTo(1L);
        assertThat(result.getMabRewardCount()).isEqualTo(1L);
        verify(listOperations).rightPush(eq("pisces:event:store:" + experimentId + ":A"), any());
        verify(listOperations).rightPush(eq("pisces:exposure:store:" + experimentId + ":A"), any());
        verify(hashOperations).increment("pisces:event:counter:" + experimentId + ":A", "PAY_SUCCESS", 1);
        verify(setOperations).add("pisces:visitor:set:" + experimentId + ":A", "visitor-1");
        verify(setOperations).add("pisces:exposure:set:" + experimentId + ":A", "visitor-1");
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_repair_payment".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION.equals(
                                materialization.getMaterializationSource())
                        && "replay_local".equals(materialization.getReplayJobId())));
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EXPOSURE.equals(materialization.getFactKind())
                        && "expo_repair_home".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION.equals(
                                materialization.getMaterializationSource())
                        && "replay_local".equals(materialization.getReplayJobId())));
    }

    @Test
    void repairUnmaterializedDerivedDataShouldOnlyRefreshLedgerWhenFactAlreadyInStore() {
        String experimentId = "exp_event_mab";
        ExperimentEventFact eventFact = eventFact("evt_existing_payment", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listUnmaterializedByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1000))).thenReturn(List.of(eventFact));
        when(listOperations.range(eq("pisces:event:store:" + experimentId + ":A"), eq(0L), eq(-1L)))
                .thenReturn(List.<Object>of(Map.of("eventId", "evt_existing_payment")));

        var result = materializer.repairUnmaterializedDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, false, "replay_local");

        assertThat(result.getEventCount()).isEqualTo(1L);
        assertThat(result.getExposureCount()).isZero();
        assertThat(result.getGroupCount()).isEqualTo(1L);
        assertThat(result.getMabRewardCount()).isZero();
        verify(listOperations, never()).rightPush(anyString(), any());
        verify(hashOperations, never()).increment(anyString(), any(), eq(1L));
        verify(setOperations, never()).add(anyString(), any());
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_existing_payment".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION.equals(
                                materialization.getMaterializationSource())
                        && "replay_local".equals(materialization.getReplayJobId())));
    }

    @Test
    void copyReplayDerivedDataShouldMaterializeScopedFactsAndRefreshLedger() {
        String experimentId = "exp_event_mab";
        ExperimentEventFact eventFact = eventFact("evt_copy_payment", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success");
        ExperimentExposure exposure = exposure("expo_copy_home", experimentId, "A",
                "visitor-1", "exp_event_mab:visitor-1:A");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1000))).thenReturn(List.of(eventFact));
        when(experimentExposureRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(0L), eq(1000))).thenReturn(List.of(exposure));
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());
        when(mabService.recordRewardObservation(experimentId, "A", "visitor-1", true)).thenReturn(true);

        var result = materializer.copyReplayDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, true, "replay_copy");

        assertThat(result.getEventCount()).isEqualTo(1L);
        assertThat(result.getExposureCount()).isEqualTo(1L);
        assertThat(result.getGroupCount()).isEqualTo(1L);
        assertThat(result.getMabRewardCount()).isEqualTo(1L);
        verify(listOperations).rightPush(eq("pisces:event:store:" + experimentId + ":A"), any());
        verify(listOperations).rightPush(eq("pisces:exposure:store:" + experimentId + ":A"), any());
        verify(hashOperations).increment("pisces:event:counter:" + experimentId + ":A", "PAY_SUCCESS", 1);
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_copy_payment".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPLAY_COPY.equals(
                                materialization.getMaterializationSource())
                        && "replay_copy".equals(materialization.getReplayJobId())));
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EXPOSURE.equals(materialization.getFactKind())
                        && "expo_copy_home".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPLAY_COPY.equals(
                                materialization.getMaterializationSource())
                        && "replay_copy".equals(materialization.getReplayJobId())));
    }

    @Test
    void copyReplayDerivedDataShouldNotDuplicateExistingDerivedFacts() {
        String experimentId = "exp_event_mab";
        ExperimentEventFact eventFact = eventFact("evt_existing_payment", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1000))).thenReturn(List.of(eventFact));
        when(listOperations.range(eq("pisces:event:store:" + experimentId + ":A"), eq(0L), eq(-1L)))
                .thenReturn(List.<Object>of(Map.of("eventId", "evt_existing_payment")));

        var result = materializer.copyReplayDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, false, "replay_copy");

        assertThat(result.getEventCount()).isEqualTo(1L);
        assertThat(result.getExposureCount()).isZero();
        assertThat(result.getGroupCount()).isEqualTo(1L);
        assertThat(result.getMabRewardCount()).isZero();
        verify(listOperations, never()).rightPush(anyString(), any());
        verify(hashOperations, never()).increment(anyString(), any(), eq(1L));
        verify(mabService, never()).recordRewardObservation(anyString(), anyString(), anyString(), anyBoolean());
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                EventMaterializationRecord.FACT_KIND_EVENT.equals(materialization.getFactKind())
                        && "evt_existing_payment".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPLAY_COPY.equals(
                                materialization.getMaterializationSource())
                        && "replay_copy".equals(materialization.getReplayJobId())));
    }

    @Test
    void copyReplayDerivedDataShouldProcessScopedFactsInBatches() {
        String experimentId = "exp_event_mab";
        ReflectionTestUtils.setField(materializer, "eventReplayBatchSize", 1);
        ExperimentEventFact firstEventFact = eventFact("evt_copy_first", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success-1");
        ExperimentEventFact secondEventFact = eventFact("evt_copy_second", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success-2");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1)))
                .thenReturn(List.of(firstEventFact));
        when(experimentEventRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(1L), eq(1)))
                .thenReturn(List.of(secondEventFact));
        when(experimentEventRepository.listByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(2L), eq(1)))
                .thenReturn(List.of());
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        var result = materializer.copyReplayDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, false, "replay_copy");

        assertThat(result.getEventCount()).isEqualTo(2L);
        assertThat(result.getExposureCount()).isZero();
        assertThat(result.getGroupCount()).isEqualTo(1L);
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                "evt_copy_first".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPLAY_COPY.equals(
                                materialization.getMaterializationSource())));
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                "evt_copy_second".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPLAY_COPY.equals(
                                materialization.getMaterializationSource())));
    }

    @Test
    void repairUnmaterializedDerivedDataShouldKeepFirstPageWhenLedgerSetShrinks() {
        String experimentId = "exp_event_mab";
        ReflectionTestUtils.setField(materializer, "eventReplayBatchSize", 1);
        ExperimentEventFact firstEventFact = eventFact("evt_repair_first", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success-1");
        ExperimentEventFact secondEventFact = eventFact("evt_repair_second", experimentId, "A",
                "PAY_SUCCESS", "client-pay_success-2");
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata());
        when(experimentEventRepository.listUnmaterializedByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(0L), eq(1)))
                .thenReturn(List.of(firstEventFact))
                .thenReturn(List.of(secondEventFact))
                .thenReturn(List.of());
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(List.of());

        var result = materializer.repairUnmaterializedDerivedData(experimentId, null, null,
                List.of("PAY_SUCCESS"), true, false, "replay_repair");

        assertThat(result.getEventCount()).isEqualTo(2L);
        assertThat(result.getExposureCount()).isZero();
        assertThat(result.getGroupCount()).isEqualTo(1L);
        verify(experimentEventRepository, never()).listUnmaterializedByReplayScopeBatch(eq(experimentId), eq("A"),
                isNull(), isNull(), eq(List.of("PAY_SUCCESS")), eq(1L), eq(1));
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                "evt_repair_first".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION.equals(
                                materialization.getMaterializationSource())));
        verify(eventMaterializationRepository).saveOrRefresh(argThat(materialization ->
                "evt_repair_second".equals(materialization.getFactId())
                        && EventMaterializationRecord.SOURCE_REPAIR_MATERIALIZATION.equals(
                                materialization.getMaterializationSource())));
    }

    private EventInboxRecord eventRecord(String experimentId, String eventType, Map<String, Object> properties) {
        EventInboxRecord record = new EventInboxRecord();
        record.setInboxId("inbox_" + eventType.toLowerCase());
        record.setExperimentId(experimentId);
        record.setVisitorId("visitor-1");
        record.setGroupId("A");
        record.setEventKind(EventInboxConstants.KIND_EVENT);
        record.setEventType(eventType);
        record.setEventName(eventType.toLowerCase());
        record.setClientEventId("client-" + eventType.toLowerCase());
        record.setProperties(properties);
        record.setEventTime(LocalDateTime.now());
        return record;
    }

    private EventInboxRecord exposureRecord(String experimentId) {
        EventInboxRecord record = new EventInboxRecord();
        record.setInboxId("inbox_exposure");
        record.setExperimentId(experimentId);
        record.setVisitorId("visitor-1");
        record.setGroupId("A");
        record.setEventKind(EventInboxConstants.KIND_EXPOSURE);
        record.setScene("home");
        record.setProperties(Map.of());
        record.setEventTime(LocalDateTime.now());
        return record;
    }

    private ExperimentEventFact eventFact(String eventId, String experimentId, String groupId, String eventType,
                                          String clientEventId) {
        ExperimentEventFact fact = new ExperimentEventFact();
        fact.setEventId(eventId);
        fact.setExperimentId(experimentId);
        fact.setVisitorId("visitor-1");
        fact.setGroupId(groupId);
        fact.setEventType(eventType);
        fact.setEventName(eventType.toLowerCase());
        fact.setClientEventId(clientEventId);
        fact.setProperties(Map.of());
        fact.setEventTime(LocalDateTime.now());
        return fact;
    }

    private ExperimentExposure exposure(String exposureId, String experimentId, String groupId, String visitorId,
                                        String idempotencyKey) {
        ExperimentExposure exposure = new ExperimentExposure();
        exposure.setExposureId(exposureId);
        exposure.setExperimentId(experimentId);
        exposure.setGroupId(groupId);
        exposure.setVisitorId(visitorId);
        exposure.setScene("home");
        exposure.setIdempotencyKey(idempotencyKey);
        exposure.setProperties(Map.of());
        exposure.setExposedAt(LocalDateTime.now());
        return exposure;
    }

    private ExperimentMetadata metadata() {
        ExperimentMetadata metadata = new ExperimentMetadata();
        ExperimentGroup group = new ExperimentGroup();
        group.setId("A");
        group.setName("control");
        Map<String, ExperimentGroup> groups = new LinkedHashMap<>();
        groups.put("A", group);
        metadata.setGroups(groups);
        metadata.setMetricDefinitions(List.of(payRateMetric()));
        return metadata;
    }

    private MetricDefinition payRateMetric() {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey("PAYMENT_RATE");
        metricDefinition.setName("支付率");
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType("PAY_SUCCESS");
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType("PRODUCT_VIEW");
        metricDefinition.setPrimaryMetric(true);
        return metricDefinition;
    }
}
