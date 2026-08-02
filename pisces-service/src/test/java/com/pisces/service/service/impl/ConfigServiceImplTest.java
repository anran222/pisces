package com.pisces.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentConfigDraft;
import com.pisces.common.model.ExperimentConfigDraftApproval;
import com.pisces.common.model.ExperimentConfigVersion;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.config.ExperimentConfigChangeBroadcaster;
import com.pisces.service.repository.ExperimentConfigRepository;
import com.pisces.service.repository.ExperimentConfigDraftApprovalRepository;
import com.pisces.service.repository.ExperimentConfigDraftRepository;
import com.pisces.service.repository.ExperimentConfigVersionRepository;
import com.pisces.service.util.JsonUtil;
import com.pisces.service.zookeeper.ZookeeperClient;
import com.pisces.service.zookeeper.ZookeeperConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigServiceImplTest {

    @Test
    void getExperimentConfigShouldStillWorkWhenZookeeperIsUnavailable() throws Exception {
        ConfigServiceImpl configService = buildConfigService(false);

        ExperimentMetadata metadata = buildMetadata("exp_config_repo_001");
        configService.saveExperimentConfig("exp_config_repo_001", metadata);

        ExperimentMetadata loaded = configService.getExperimentConfig("exp_config_repo_001");

        assertThat(loaded).isNotNull();
        assertThat(loaded.getExperiment()).isNotNull();
        assertThat(loaded.getExperiment().getId()).isEqualTo("exp_config_repo_001");
        assertThat(loaded.getConfigVersion()).isEqualTo(3L);
    }

    @Test
    void getAllExperimentIdsShouldStillWorkWhenZookeeperIsUnavailable() throws Exception {
        ConfigServiceImpl configService = buildConfigService(false);

        configService.saveExperimentConfig("exp_config_repo_001", buildMetadata("exp_config_repo_001"));
        configService.saveExperimentConfig("exp_config_repo_002", buildMetadata("exp_config_repo_002"));

        List<String> experimentIds = configService.getAllExperimentIds();

        assertThat(experimentIds)
                .containsExactlyInAnyOrder("exp_config_repo_001", "exp_config_repo_002");
    }

    @Test
    void waitForExperimentConfigChangeShouldNotMissAlreadyPublishedSignal() throws Exception {
        ConfigServiceImpl configService = buildConfigService(false);
        long observedSequence = configService.getExperimentConfigChangeSequence("exp_config_repo_001");

        configService.saveExperimentConfig("exp_config_repo_001", buildMetadata("exp_config_repo_001"));
        long startedAt = System.currentTimeMillis();
        configService.waitForExperimentConfigChange("exp_config_repo_001", observedSequence, 1_000L);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertThat(configService.getExperimentConfigChangeSequence("exp_config_repo_001"))
                .isGreaterThan(observedSequence);
        assertThat(elapsedMillis).isLessThan(200L);
    }

    @Test
    void saveExperimentConfigShouldPublishConfigChangeBroadcast() throws Exception {
        StubExperimentConfigChangeBroadcaster broadcaster = new StubExperimentConfigChangeBroadcaster();
        ConfigServiceImpl configService = buildConfigService(false, broadcaster);

        configService.saveExperimentConfig("exp_config_repo_001", buildMetadata("exp_config_repo_001"));

        assertThat(broadcaster.publishedExperimentIds).containsExactly("exp_config_repo_001");
    }

    @Test
    void remoteExperimentConfigChangeShouldWakeConfigWaiters() throws Exception {
        StubExperimentConfigChangeBroadcaster broadcaster = new StubExperimentConfigChangeBroadcaster();
        ConfigServiceImpl configService = buildConfigService(false, broadcaster);
        configService.init();
        long observedSequence = configService.getExperimentConfigChangeSequence("exp_config_repo_001");

        broadcaster.emitExperimentChange("exp_config_repo_001");
        configService.waitForExperimentConfigChange("exp_config_repo_001", observedSequence, 1_000L);

        assertThat(configService.getExperimentConfigChangeSequence("exp_config_repo_001"))
                .isGreaterThan(observedSequence);
    }

    private ConfigServiceImpl buildConfigService(boolean connected) {
        return buildConfigService(connected, null);
    }

    private ConfigServiceImpl buildConfigService(boolean connected,
                                                 ExperimentConfigChangeBroadcaster broadcaster) {
        ZookeeperConfig zookeeperConfig = new ZookeeperConfig();
        zookeeperConfig.setBasePath("/pisces-test");
        ConfigServiceImpl configService = new ConfigServiceImpl(
                new StubZookeeperClient(connected),
                zookeeperConfig,
                new StubExperimentConfigRepository(),
                new StubExperimentConfigVersionRepository(),
                new StubExperimentConfigDraftRepository(),
                new StubExperimentConfigDraftApprovalRepository()
        );
        if (broadcaster != null) {
            ReflectionTestUtils.setField(configService, "experimentConfigChangeBroadcaster", broadcaster);
        }
        return configService;
    }

    private ExperimentMetadata buildMetadata(String experimentId) {
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName("配置持久化测试实验");
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setCreateTime(LocalDateTime.now());
        experiment.setUpdateTime(LocalDateTime.now());

        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(3L);
        metadata.setExperiment(experiment);
        metadata.setGroups(Map.of());
        return metadata;
    }

    private static final class StubZookeeperClient extends ZookeeperClient {

        private final boolean connected;

        private StubZookeeperClient(boolean connected) {
            super(new ZookeeperConfig(), new JsonUtil(new ObjectMapper()));
            this.connected = connected;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static final class StubExperimentConfigRepository implements ExperimentConfigRepository {

        private final Map<String, ExperimentMetadata> data = new LinkedHashMap<>();

        @Override
        public void save(String experimentId, ExperimentMetadata metadata) {
            data.put(experimentId, metadata);
        }

        @Override
        public Optional<ExperimentMetadata> findById(String experimentId) {
            return Optional.ofNullable(data.get(experimentId));
        }

        @Override
        public void delete(String experimentId) {
            data.remove(experimentId);
        }

        @Override
        public List<String> findAllExperimentIds() {
            return new ArrayList<>(data.keySet());
        }
    }

    private static final class StubExperimentConfigVersionRepository implements ExperimentConfigVersionRepository {

        @Override
        public ExperimentConfigVersion save(String experimentId, ExperimentMetadata metadata, String publishedBy,
                                            String publishComment, Long sourceConfigVersion, String sourceType) {
            ExperimentConfigVersion version = new ExperimentConfigVersion();
            version.setExperimentId(experimentId);
            version.setConfigVersion(metadata.getConfigVersion());
            version.setMetadata(metadata);
            version.setPublishedBy(publishedBy);
            version.setPublishComment(publishComment);
            version.setSourceConfigVersion(sourceConfigVersion);
            version.setSourceType(sourceType);
            return version;
        }

        @Override
        public List<ExperimentConfigVersion> listByExperimentId(String experimentId) {
            return List.of();
        }

        @Override
        public Optional<ExperimentConfigVersion> findByExperimentIdAndVersion(String experimentId,
                                                                              long configVersion) {
            return Optional.empty();
        }
    }

    private static final class StubExperimentConfigDraftRepository implements ExperimentConfigDraftRepository {

        @Override
        public ExperimentConfigDraft save(String experimentId, ExperimentMetadata metadata, long baseConfigVersion,
                                          String updatedBy, String draftComment) {
            ExperimentConfigDraft draft = new ExperimentConfigDraft();
            draft.setExperimentId(experimentId);
            draft.setDraftVersion(1L);
            draft.setBaseConfigVersion(baseConfigVersion);
            draft.setMetadata(metadata);
            draft.setUpdatedBy(updatedBy);
            draft.setDraftComment(draftComment);
            return draft;
        }

        @Override
        public Optional<ExperimentConfigDraft> findByExperimentId(String experimentId) {
            return Optional.empty();
        }

        @Override
        public void delete(String experimentId) {
        }
    }

    private static final class StubExperimentConfigDraftApprovalRepository
            implements ExperimentConfigDraftApprovalRepository {

        private final Map<String, ExperimentConfigDraftApproval> data = new LinkedHashMap<>();

        @Override
        public ExperimentConfigDraftApproval save(ExperimentConfigDraftApproval approval) {
            data.put(buildKey(approval.getExperimentId(), approval.getDraftVersion()), approval);
            return approval;
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> findByExperimentIdAndDraftVersion(String experimentId,
                                                                                         long draftVersion) {
            return Optional.ofNullable(data.get(buildKey(experimentId, draftVersion)));
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> findLatestByExperimentId(String experimentId) {
            return data.values().stream()
                    .filter(approval -> experimentId.equals(approval.getExperimentId()))
                    .max((left, right) -> Long.compare(left.getDraftVersion(), right.getDraftVersion()));
        }

        @Override
        public List<ExperimentConfigDraftApproval> listByExperimentId(String experimentId) {
            return data.values().stream()
                    .filter(approval -> experimentId.equals(approval.getExperimentId()))
                    .sorted((left, right) -> Long.compare(right.getDraftVersion(), left.getDraftVersion()))
                    .toList();
        }

        @Override
        public Optional<ExperimentConfigDraftApproval> updateStatus(String experimentId, long draftVersion,
                                                                    ExperimentMetadata.ApprovalStatus approvalStatus,
                                                                    String approvalOperator, String approvalComment) {
            return findByExperimentIdAndDraftVersion(experimentId, draftVersion)
                    .map(approval -> {
                        approval.setApprovalStatus(approvalStatus);
                        approval.setApprovalOperator(approvalOperator);
                        approval.setApprovalComment(approvalComment);
                        approval.setApprovalUpdatedAt(LocalDateTime.now());
                        return approval;
                    });
        }

        private String buildKey(String experimentId, Long draftVersion) {
            return experimentId + ":" + draftVersion;
        }
    }

    private static final class StubExperimentConfigChangeBroadcaster implements ExperimentConfigChangeBroadcaster {

        private final List<String> publishedExperimentIds = new ArrayList<>();

        private Consumer<String> listener;

        @Override
        public void publishExperimentChange(String experimentId) {
            publishedExperimentIds.add(experimentId);
        }

        @Override
        public void addExperimentChangeListener(Consumer<String> listener) {
            this.listener = listener;
        }

        private void emitExperimentChange(String experimentId) {
            if (listener != null) {
                listener.accept(experimentId);
            }
        }
    }
}
