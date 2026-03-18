package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.repository.ExperimentConfigRepository;
import com.pisces.service.zookeeper.ZookeeperClient;
import com.pisces.service.zookeeper.ZookeeperConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private ConfigServiceImpl buildConfigService(boolean connected) {
        ZookeeperConfig zookeeperConfig = new ZookeeperConfig();
        zookeeperConfig.setBasePath("/pisces-test");
        return new ConfigServiceImpl(
                new StubZookeeperClient(connected),
                zookeeperConfig,
                new StubExperimentConfigRepository()
        );
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
}
