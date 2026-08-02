package com.pisces.service.service.impl;

import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiArmedBanditServiceImplTest {

    @Mock
    private ConfigService configService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private MultiArmedBanditServiceImpl multiArmedBanditService;

    @Test
    void getMABSummaryShouldReadBetaParamsOncePerGroup() {
        String experimentId = "exp_mab";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A", "B"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(hashOperations.get("pisces:mab:beta:" + experimentId, "A"))
                .thenReturn(Map.of("alpha", 41, "beta", 11));
        when(hashOperations.get("pisces:mab:beta:" + experimentId, "B"))
                .thenReturn(Map.of("alpha", 21, "beta", 31));
        when(hashOperations.get("pisces:mab:ucb:" + experimentId, "A"))
                .thenReturn(Map.of("trials", 50L, "successes", 40L, "averageReward", 0.8D));
        when(hashOperations.get("pisces:mab:ucb:" + experimentId, "B"))
                .thenReturn(Map.of("trials", 50L, "successes", 20L, "averageReward", 0.4D));
        when(valueOperations.get("pisces:mab:trials:" + experimentId)).thenReturn(100L);

        Map<String, Object> summary = multiArmedBanditService.getMABSummary(experimentId);

        assertThat(summary).containsEntry("experimentId", experimentId);
        assertThat(summary.get("totalTrials")).isEqualTo(100L);
        assertThat(summary.get("ucbSelectionTrials")).isEqualTo(100L);
        assertThat(summary.get("totalObservedRewards")).isEqualTo(100L);
        assertThat(summary.get("leadingGroup")).isEqualTo("A");
        assertThat(summary.get("allocationProbabilities")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> groupDetails = (Map<String, Object>) summary.get("groupDetails");
        @SuppressWarnings("unchecked")
        Map<String, Object> groupA = (Map<String, Object>) groupDetails.get("A");
        assertThat(groupA).containsEntry("observedRewardCount", 50);
        assertThat(groupA).containsEntry("observedSuccesses", 40);
        assertThat(groupA).containsEntry("observedFailures", 10);
        assertThat(groupA).containsEntry("ucbTrials", 50L);
        verify(hashOperations).get("pisces:mab:beta:" + experimentId, "A");
        verify(hashOperations).get("pisces:mab:beta:" + experimentId, "B");
    }

    @Test
    void getMABSummaryShouldUseZeroSuccessRateBeforeRewards() {
        String experimentId = "exp_empty_mab";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Map<String, Object> summary = multiArmedBanditService.getMABSummary(experimentId);

        @SuppressWarnings("unchecked")
        Map<String, Object> groupDetails = (Map<String, Object>) summary.get("groupDetails");
        @SuppressWarnings("unchecked")
        Map<String, Object> groupA = (Map<String, Object>) groupDetails.get("A");
        assertThat(groupA).containsEntry("successRate", 0.0D);
    }

    @Test
    void getMABSummaryShouldUseObservedRewardCountWhenSelectionTrialsAreEmpty() {
        String experimentId = "exp_observed_mab";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(hashOperations.get("pisces:mab:beta:" + experimentId, "A"))
                .thenReturn(Map.of("alpha", 3, "beta", 2));

        Map<String, Object> summary = multiArmedBanditService.getMABSummary(experimentId);

        assertThat(summary.get("totalTrials")).isEqualTo(3L);
        assertThat(summary.get("ucbSelectionTrials")).isEqualTo(0L);
        assertThat(summary.get("totalObservedRewards")).isEqualTo(3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> groupDetails = (Map<String, Object>) summary.get("groupDetails");
        @SuppressWarnings("unchecked")
        Map<String, Object> groupA = (Map<String, Object>) groupDetails.get("A");
        assertThat(groupA).containsEntry("trials", 3L);
        assertThat(groupA).containsEntry("successes", 2L);
        assertThat(groupA).containsEntry("averageReward", 2.0D / 3.0D);
    }

    @Test
    void updateRewardShouldStoreObservedRewardWithoutIncrementingSelectionTrials() {
        String experimentId = "exp_reward";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        multiArmedBanditService.updateReward(experimentId, "A", true);

        verify(hashOperations).put(eq("pisces:mab:beta:" + experimentId), eq("A"),
                argThat(value -> {
                    Map<?, ?> params = (Map<?, ?>) value;
                    return Integer.valueOf(2).equals(params.get("alpha"))
                            && Integer.valueOf(1).equals(params.get("beta"));
                }));
        verify(hashOperations).put(eq("pisces:mab:ucb:" + experimentId), eq("A"),
                argThat(value -> {
                    Map<?, ?> stats = (Map<?, ?>) value;
                    return Long.valueOf(0L).equals(stats.get("trials"))
                            && Long.valueOf(1L).equals(stats.get("successes"))
                            && Double.valueOf(0.0D).equals(stats.get("averageReward"));
                }));
        verify(valueOperations, never()).increment("pisces:mab:trials:" + experimentId);
    }

    @Test
    void recordRewardObservationShouldRecordFailureAndUpgradeToSuccess() {
        String experimentId = "exp_reward_observation";
        String betaKey = "pisces:mab:beta:" + experimentId;
        String observationKey = "pisces:mab:reward-observation:" + experimentId + ":A";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(observationKey, "visitor-1")).thenReturn(null, "FAILURE");
        when(hashOperations.get(betaKey, "A")).thenReturn(null, Map.of("alpha", 1, "beta", 2));

        boolean failureRecorded = multiArmedBanditService.recordRewardObservation(
                experimentId, "A", "visitor-1", false);
        boolean upgraded = multiArmedBanditService.recordRewardObservation(
                experimentId, "A", "visitor-1", true);

        assertThat(failureRecorded).isTrue();
        assertThat(upgraded).isTrue();
        verify(hashOperations).put(eq(observationKey), eq("visitor-1"), eq("FAILURE"));
        verify(hashOperations).put(eq(observationKey), eq("visitor-1"), eq("SUCCESS"));
        verify(hashOperations).put(eq(betaKey), eq("A"),
                argThat(value -> {
                    Map<?, ?> params = (Map<?, ?>) value;
                    return Integer.valueOf(1).equals(params.get("alpha"))
                            && Integer.valueOf(2).equals(params.get("beta"));
                }));
        verify(hashOperations).put(eq(betaKey), eq("A"),
                argThat(value -> {
                    Map<?, ?> params = (Map<?, ?>) value;
                    return Integer.valueOf(2).equals(params.get("alpha"))
                            && Integer.valueOf(1).equals(params.get("beta"));
                }));
    }

    @Test
    void recordRewardObservationShouldNotDowngradeSuccess() {
        String experimentId = "exp_no_downgrade";
        String betaKey = "pisces:mab:beta:" + experimentId;
        String observationKey = "pisces:mab:reward-observation:" + experimentId + ":A";
        when(configService.getExperimentConfig(experimentId)).thenReturn(metadata("A"));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(observationKey, "visitor-1")).thenReturn("SUCCESS");

        boolean updated = multiArmedBanditService.recordRewardObservation(
                experimentId, "A", "visitor-1", false);

        assertThat(updated).isFalse();
        verify(hashOperations, never()).put(eq(betaKey), eq("A"), any());
        verify(hashOperations, never()).put(eq(observationKey), eq("visitor-1"), any());
    }

    private ExperimentMetadata metadata(String... groupIds) {
        ExperimentMetadata metadata = new ExperimentMetadata();
        Map<String, ExperimentGroup> groups = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            ExperimentGroup group = new ExperimentGroup();
            group.setId(groupId);
            group.setName("group-" + groupId);
            groups.put(groupId, group);
        }
        metadata.setGroups(groups);
        return metadata;
    }
}
