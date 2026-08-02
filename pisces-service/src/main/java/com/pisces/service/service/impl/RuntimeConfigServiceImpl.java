package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentGroup;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.response.RuntimeExperimentConfigResponse;
import com.pisces.common.response.RuntimeExperimentConfigVersionResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运行时配置服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:49
 */
@Service
@RequiredArgsConstructor
public class RuntimeConfigServiceImpl implements RuntimeConfigService {

    private static final long MAX_CONFIG_VERSION_WAIT_MILLIS = 30_000L;

    private final ConfigService configService;

    @Override
    public RuntimeExperimentConfigResponse getExperimentConfig(String experimentId) {
        ExperimentMetadata metadata = getVisibleExperimentMetadata(experimentId);
        return convertToRuntimeConfig(metadata);
    }

    @Override
    public RuntimeExperimentConfigVersionResponse getExperimentConfigVersion(String experimentId, Long knownVersion,
                                                                            Long waitMillis) {
        long normalizedWaitMillis = normalizeWaitMillis(waitMillis);
        long observedChangeSequence = resolveObservedChangeSequence(experimentId, knownVersion, normalizedWaitMillis);
        ExperimentMetadata metadata = getVisibleExperimentMetadata(experimentId);
        Experiment experiment = metadata.getExperiment();
        Long currentVersion = metadata.getConfigVersion();
        if (normalizedWaitMillis > 0 && knownVersion != null && knownVersion.equals(currentVersion)) {
            waitForExperimentConfigChange(experimentId, observedChangeSequence, normalizedWaitMillis);
            metadata = getVisibleExperimentMetadata(experimentId);
            experiment = metadata.getExperiment();
            currentVersion = metadata.getConfigVersion();
        }

        RuntimeExperimentConfigVersionResponse response = new RuntimeExperimentConfigVersionResponse();
        response.setExperimentId(experiment.getId());
        response.setKnownVersion(knownVersion);
        response.setCurrentVersion(currentVersion);
        response.setChanged(knownVersion == null || !knownVersion.equals(currentVersion));
        response.setStatus(experiment.getStatus() != null ? experiment.getStatus().name() : null);
        response.setGeneratedAt(LocalDateTime.now());
        return response;
    }

    private long resolveObservedChangeSequence(String experimentId, Long knownVersion, long waitMillis) {
        if (waitMillis <= 0 || knownVersion == null) {
            return 0L;
        }
        return configService.getExperimentConfigChangeSequence(experimentId);
    }

    private void waitForExperimentConfigChange(String experimentId, long observedChangeSequence, long waitMillis) {
        try {
            configService.waitForExperimentConfigChange(experimentId, observedChangeSequence, waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "等待实验配置变更被中断");
        }
    }

    private long normalizeWaitMillis(Long waitMillis) {
        if (waitMillis == null || waitMillis <= 0) {
            return 0L;
        }
        return Math.min(waitMillis, MAX_CONFIG_VERSION_WAIT_MILLIS);
    }

    private ExperimentMetadata getVisibleExperimentMetadata(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getExperiment() == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        normalizeExperimentOwnership(metadata);
        ApiKeyContextHolder.assertCanAccess(metadata);
        return metadata;
    }

    private RuntimeExperimentConfigResponse convertToRuntimeConfig(ExperimentMetadata metadata) {
        Experiment experiment = metadata.getExperiment();
        RuntimeExperimentConfigResponse response = new RuntimeExperimentConfigResponse();
        response.setId(experiment.getId());
        response.setName(experiment.getName());
        response.setDescription(experiment.getDescription());
        response.setStatus(experiment.getStatus() != null ? experiment.getStatus().name() : null);
        response.setConfigVersion(metadata.getConfigVersion());
        response.setEventDefinitions(emptyListIfNull(metadata.getEventDefinitions()));
        response.setMetricDefinitions(emptyListIfNull(metadata.getMetricDefinitions()));
        response.setGroupConfigSchema(emptyListIfNull(metadata.getGroupConfigSchema()));
        response.setGroups(convertGroups(metadata.getGroups()));
        response.setTraffic(convertTrafficConfig(metadata.getTraffic()));
        return response;
    }

    private static <T> List<T> emptyListIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, RuntimeExperimentConfigResponse.GroupConfigResponse> convertGroups(
            Map<String, ExperimentGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        Map<String, RuntimeExperimentConfigResponse.GroupConfigResponse> response = new LinkedHashMap<>();
        groups.forEach((groupId, group) -> response.put(groupId, convertGroup(group)));
        return response;
    }

    private RuntimeExperimentConfigResponse.GroupConfigResponse convertGroup(ExperimentGroup group) {
        RuntimeExperimentConfigResponse.GroupConfigResponse response =
                new RuntimeExperimentConfigResponse.GroupConfigResponse();
        response.setId(group.getId());
        response.setName(group.getName());
        response.setTrafficRatio(group.getTrafficRatio());
        response.setConfig(group.getConfig() == null ? Map.of() : group.getConfig());
        return response;
    }

    private RuntimeExperimentConfigResponse.TrafficConfigResponse convertTrafficConfig(TrafficConfig trafficConfig) {
        if (trafficConfig == null) {
            return null;
        }
        RuntimeExperimentConfigResponse.TrafficConfigResponse response =
                new RuntimeExperimentConfigResponse.TrafficConfigResponse();
        response.setTotalTraffic(trafficConfig.getTotalTraffic());
        response.setStrategy(trafficConfig.getStrategy() != null ? trafficConfig.getStrategy().name() : null);
        response.setHashKey(trafficConfig.getHashKey());
        response.setAllocation(convertAllocations(trafficConfig.getAllocation()));
        return response;
    }

    private List<RuntimeExperimentConfigResponse.GroupAllocationResponse> convertAllocations(
            List<TrafficConfig.GroupAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return List.of();
        }
        return allocations.stream()
                .map(this::convertAllocation)
                .collect(Collectors.toList());
    }

    private RuntimeExperimentConfigResponse.GroupAllocationResponse convertAllocation(
            TrafficConfig.GroupAllocation allocation) {
        RuntimeExperimentConfigResponse.GroupAllocationResponse response =
                new RuntimeExperimentConfigResponse.GroupAllocationResponse();
        response.setGroup(allocation.getGroup());
        response.setRatio(allocation.getRatio());
        return response;
    }

    private void normalizeExperimentOwnership(ExperimentMetadata metadata) {
        String appId = ApiKeyContextHolder.resolveMetadataAppId(metadata);
        String owner = ApiKeyContextHolder.resolveMetadataOwner(metadata);
        metadata.setAppId(appId);
        metadata.setOwner(owner);
        metadata.getExperiment().setAppId(appId);
        metadata.getExperiment().setOwner(owner);
    }
}
