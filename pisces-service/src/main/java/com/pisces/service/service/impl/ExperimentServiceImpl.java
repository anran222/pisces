package com.pisces.service.service.impl;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.request.ExperimentConclusionStatusUpdateRequest;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentResponse;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.conclusion.ExperimentConclusionStatusPolicy;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import com.pisces.common.model.ExperimentReportSnapshot;
import org.springframework.beans.BeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 实验管理服务实现
 */
@Slf4j
@Service
public class ExperimentServiceImpl implements ExperimentService {

    private static final Pattern DEFINITION_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    
    @Autowired
    private ConfigService configService;

    @Autowired
    private TrafficRuleEvaluator trafficRuleEvaluator;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private GroupConfigSchemaValidator groupConfigSchemaValidator;
    
    /**
     * 创建实验（无用户系统版本）
     */
    @Override
    public Experiment createExperiment(ExperimentCreateRequest request) {
        List<GroupConfigFieldDefinition> groupConfigSchema =
                groupConfigSchemaValidator.normalizeSchema(request.getGroupConfigSchema());
        // 参数校验
        validateExperimentRequest(request, groupConfigSchema);
        
        // 生成实验ID
        String experimentId = "exp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        // 创建实验对象
        Experiment experiment = new Experiment();
        experiment.setId(experimentId);
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStatus(Experiment.ExperimentStatus.DRAFT);
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setCreator("system");  // 无用户系统，使用system作为创建者
        experiment.setCreateTime(LocalDateTime.now());
        experiment.setUpdateTime(LocalDateTime.now());
        
        // 构建实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new LinkedHashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema,
                        groupConfig.getConfig(), groupConfig.getId()));
                groups.put(group.getId(), group);
            }
        }
        
        // 构建流量配置
        TrafficConfig trafficConfig = buildTrafficConfig(request.getTraffic());
        
        // 构建实验元数据
        ExperimentMetadata metadata = new ExperimentMetadata();
        metadata.setConfigVersion(1L);
        metadata.setExperiment(experiment);
        metadata.setGroups(groups);
        metadata.setTraffic(trafficConfig);
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        metadata.setEventDefinitions(resolveEventDefinitions(request));
        metadata.setMetricDefinitions(resolveMetricDefinitions(request));
        metadata.setGroupConfigSchema(groupConfigSchema);
        metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.NOT_READY);
        metadata.setConclusionUpdatedAt(LocalDateTime.now());
        
        // 保存到Zookeeper
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("创建实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 更新实验（无用户系统版本）
     */
    @Override
    public Experiment updateExperiment(String experimentId, ExperimentCreateRequest request) {
        List<GroupConfigFieldDefinition> groupConfigSchema =
                groupConfigSchemaValidator.normalizeSchema(request.getGroupConfigSchema());
        validateExperimentRequest(request, groupConfigSchema);
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        experiment.setName(request.getName());
        experiment.setDescription(request.getDescription());
        experiment.setStartTime(request.getStartTime());
        experiment.setEndTime(request.getEndTime());
        experiment.setUpdateTime(LocalDateTime.now());
        
        // 更新实验组
        Map<String, com.pisces.common.model.ExperimentGroup> groups = new LinkedHashMap<>();
        if (request.getGroups() != null) {
            for (ExperimentCreateRequest.GroupConfig groupConfig : request.getGroups()) {
                com.pisces.common.model.ExperimentGroup group = new com.pisces.common.model.ExperimentGroup();
                group.setId(groupConfig.getId());
                group.setName(groupConfig.getName());
                group.setTrafficRatio(groupConfig.getTrafficRatio());
                group.setConfig(groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema,
                        groupConfig.getConfig(), groupConfig.getId()));
                groups.put(group.getId(), group);
            }
        }
        metadata.setGroups(groups);
        
        // 更新流量配置
        TrafficConfig trafficConfig = buildTrafficConfig(request.getTraffic());
        metadata.setTraffic(trafficConfig);
        
        // 更新白名单和黑名单
        metadata.setWhitelist(request.getWhitelist() != null ? request.getWhitelist() : new ArrayList<>());
        metadata.setBlacklist(request.getBlacklist() != null ? request.getBlacklist() : new ArrayList<>());
        metadata.setEventDefinitions(resolveEventDefinitions(request));
        metadata.setMetricDefinitions(resolveMetricDefinitions(request));
        metadata.setGroupConfigSchema(groupConfigSchema);
        metadata.setConfigVersion(Math.max(1L, metadata.getConfigVersion()) + 1);
        if (metadata.getConclusionStatus() == null) {
            metadata.setConclusionStatus(ExperimentMetadata.ConclusionStatus.NOT_READY);
        }
        if (metadata.getConclusionUpdatedAt() == null) {
            metadata.setConclusionUpdatedAt(LocalDateTime.now());
        }
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("更新实验成功: {}", experimentId);
        return experiment;
    }
    
    /**
     * 启动实验（无用户系统版本）
     */
    @Override
    public void startExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.DRAFT) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有草稿状态的实验才能启动");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("启动实验: {}", experimentId);
    }
    
    /**
     * 停止实验（无用户系统版本）
     */
    @Override
    public void stopExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        experiment.setStatus(Experiment.ExperimentStatus.STOPPED);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("停止实验: {}", experimentId);
    }
    
    /**
     * 暂停实验（无用户系统版本）
     */
    @Override
    public void pauseExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.RUNNING) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有运行中的实验才能暂停");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.PAUSED);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("暂停实验: {}", experimentId);
    }
    
    /**
     * 恢复实验（从暂停状态恢复到运行状态）
     */
    @Override
    public void resumeExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        Experiment experiment = metadata.getExperiment();
        if (experiment.getStatus() != Experiment.ExperimentStatus.PAUSED) {
            throw new BusinessException(ResponseCode.EXPERIMENT_STATUS_ERROR, "只有暂停状态的实验才能恢复");
        }
        
        experiment.setStatus(Experiment.ExperimentStatus.RUNNING);
        experiment.setUpdateTime(LocalDateTime.now());
        
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            log.error("保存实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验配置失败: " + e.getMessage());
        }
        
        log.info("恢复实验: {}", experimentId);
    }
    
    /**
     * 获取实验
     */
    @Override
    public ExperimentResponse getExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        enrichSuggestedConclusion(experimentId, metadata);
        return convertToResponse(metadata);
    }

    @Override
    public void updateConclusionStatus(String experimentId, ExperimentConclusionStatusUpdateRequest request) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }

        ExperimentMetadata.ConclusionStatus targetStatus;
        try {
            targetStatus = ExperimentMetadata.ConclusionStatus.ofOrThrow(request.getConclusionStatus());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, e.getMessage());
        }

        ExperimentMetadata.ConclusionStatus currentStatus = metadata.getConclusionStatus() != null
                ? metadata.getConclusionStatus() : ExperimentMetadata.ConclusionStatus.NOT_READY;
        validateConclusionStatusTransition(currentStatus, targetStatus);

        metadata.setConclusionStatus(targetStatus);
        metadata.setConclusionUpdatedAt(LocalDateTime.now());
        try {
            configService.saveExperimentConfig(experimentId, metadata);
        } catch (Exception e) {
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "保存实验结论状态失败: " + e.getMessage());
        }
        log.info("更新实验结论状态成功: experimentId={}, from={}, to={}, operator={}",
                experimentId, currentStatus, targetStatus, request.getOperator());
    }
    
    /**
     * 转换为响应对象
     */
    private ExperimentResponse convertToResponse(ExperimentMetadata metadata) {
        ExperimentResponse response = new ExperimentResponse();
        BeanUtils.copyProperties(metadata.getExperiment(), response);
        
        // 转换实验组
        if (metadata.getGroups() != null) {
            Map<String, ExperimentResponse.GroupResponse> groups = metadata.getGroups().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                ExperimentResponse.GroupResponse gr = new ExperimentResponse.GroupResponse();
                                gr.setId(e.getValue().getId());
                                gr.setName(e.getValue().getName());
                                gr.setTrafficRatio(e.getValue().getTrafficRatio());
                                gr.setConfig(e.getValue().getConfig());
                                return gr;
                            }
                    ));
            response.setGroups(groups);
        }
        
        // 转换流量配置
        if (metadata.getTraffic() != null) {
            ExperimentResponse.TrafficConfigResponse traffic = new ExperimentResponse.TrafficConfigResponse();
            traffic.setTotalTraffic(metadata.getTraffic().getTotalTraffic());
            traffic.setStrategy(metadata.getTraffic().getStrategy() != null
                    ? metadata.getTraffic().getStrategy().name() : null);
            traffic.setHashKey(metadata.getTraffic().getHashKey());
            traffic.setRuleFallbackStrategy(metadata.getTraffic().getRuleFallbackStrategy() != null
                    ? metadata.getTraffic().getRuleFallbackStrategy().name() : null);
            
            if (metadata.getTraffic().getAllocation() != null) {
                List<ExperimentResponse.GroupAllocationResponse> allocations = 
                        metadata.getTraffic().getAllocation().stream()
                                .map(a -> {
                                    ExperimentResponse.GroupAllocationResponse ar = 
                                            new ExperimentResponse.GroupAllocationResponse();
                                    ar.setGroup(a.getGroup());
                                    ar.setRatio(a.getRatio());
                                    return ar;
                                })
                                .collect(Collectors.toList());
                traffic.setAllocation(allocations);
            }

            if (metadata.getTraffic().getRules() != null) {
                List<ExperimentResponse.TrafficRuleResponse> trafficRules = metadata.getTraffic().getRules().stream()
                        .map(rule -> {
                            ExperimentResponse.TrafficRuleResponse responseRule =
                                    new ExperimentResponse.TrafficRuleResponse();
                            responseRule.setName(rule.getName());
                            responseRule.setPriority(rule.getPriority());
                            responseRule.setGroup(rule.getGroup());
                            if (rule.getConditions() != null) {
                                List<ExperimentResponse.RuleConditionResponse> conditions = rule.getConditions().stream()
                                        .map(condition -> {
                                            ExperimentResponse.RuleConditionResponse responseCondition =
                                                    new ExperimentResponse.RuleConditionResponse();
                                            responseCondition.setField(condition.getField());
                                            responseCondition.setOperator(condition.getOperator() != null
                                                    ? condition.getOperator().name() : null);
                                            responseCondition.setValue(condition.getValue());
                                            responseCondition.setValues(condition.getValues());
                                            return responseCondition;
                                        })
                                        .collect(Collectors.toList());
                                responseRule.setConditions(conditions);
                            }
                            return responseRule;
                        })
                        .collect(Collectors.toList());
                traffic.setRules(trafficRules);
            }
            
            response.setTraffic(traffic);
        }
        
        response.setWhitelist(metadata.getWhitelist());
        response.setBlacklist(metadata.getBlacklist());
        response.setEventDefinitions(metadata.getEventDefinitions());
        response.setMetricDefinitions(metadata.getMetricDefinitions());
        response.setGroupConfigSchema(metadata.getGroupConfigSchema());
        response.setConclusionStatus(metadata.getConclusionStatus());
        response.setConclusionUpdatedAt(metadata.getConclusionUpdatedAt());
        response.setSuggestedConclusionStatus(metadata.getSuggestedConclusionStatus());
        response.setSuggestedConclusionUpdatedAt(metadata.getSuggestedConclusionUpdatedAt());
        
        return response;
    }

    private void enrichSuggestedConclusion(String experimentId, ExperimentMetadata metadata) {
        try {
            List<ExperimentReportSnapshot> reportSnapshots = analysisService.listReportSnapshots(experimentId);
            metadata.setSuggestedConclusionStatus(ExperimentConclusionStatusPolicy.resolveSuggestedStatus(reportSnapshots));
            metadata.setSuggestedConclusionUpdatedAt(
                    ExperimentConclusionStatusPolicy.resolveSuggestedUpdatedAt(reportSnapshots));
        } catch (Exception e) {
            log.debug("加载实验建议状态失败: experimentId={}", experimentId, e);
        }
    }

    private List<EventDefinition> resolveEventDefinitions(ExperimentCreateRequest request) {
        return normalizeEventDefinitions(request.getEventDefinitions());
    }

    private List<MetricDefinition> resolveMetricDefinitions(ExperimentCreateRequest request) {
        return normalizeMetricDefinitions(request.getMetricDefinitions());
    }

    private TrafficConfig buildTrafficConfig(ExperimentCreateRequest.TrafficConfigRequest trafficRequest) {
        if (trafficRequest == null) {
            return null;
        }

        TrafficConfig trafficConfig = new TrafficConfig();
        trafficConfig.setTotalTraffic(trafficRequest.getTotalTraffic());
        trafficConfig.setStrategy(TrafficConfig.TrafficStrategy.ofOrThrow(trafficRequest.getStrategy()));
        trafficConfig.setHashKey(trafficRequest.getHashKey());
        trafficConfig.setRuleFallbackStrategy(trafficRequest.getRuleFallbackStrategy() != null
                ? TrafficConfig.RuleFallbackStrategy.ofOrThrow(trafficRequest.getRuleFallbackStrategy())
                : null);

        if (trafficRequest.getAllocation() != null) {
            List<TrafficConfig.GroupAllocation> allocations = new ArrayList<>();
            for (ExperimentCreateRequest.GroupAllocationRequest allocationRequest : trafficRequest.getAllocation()) {
                TrafficConfig.GroupAllocation allocation = new TrafficConfig.GroupAllocation();
                allocation.setGroup(allocationRequest.getGroup());
                allocation.setRatio(allocationRequest.getRatio());
                allocations.add(allocation);
            }
            trafficConfig.setAllocation(allocations);
        }

        if (trafficRequest.getRules() != null) {
            List<TrafficConfig.TrafficRule> trafficRules = new ArrayList<>();
            for (ExperimentCreateRequest.TrafficRuleRequest ruleRequest : trafficRequest.getRules()) {
                TrafficConfig.TrafficRule trafficRule = new TrafficConfig.TrafficRule();
                trafficRule.setName(ruleRequest.getName());
                trafficRule.setPriority(ruleRequest.getPriority());
                trafficRule.setGroup(ruleRequest.getGroup());

                if (ruleRequest.getConditions() != null) {
                    List<TrafficConfig.RuleCondition> conditions = new ArrayList<>();
                    for (ExperimentCreateRequest.RuleConditionRequest conditionRequest : ruleRequest.getConditions()) {
                        TrafficConfig.RuleCondition condition = new TrafficConfig.RuleCondition();
                        condition.setField(conditionRequest.getField());
                        condition.setOperator(TrafficConfig.RuleOperator.ofOrThrow(conditionRequest.getOperator()));
                        condition.setValue(conditionRequest.getValue());
                        condition.setValues(conditionRequest.getValues());
                        conditions.add(condition);
                    }
                    trafficRule.setConditions(conditions);
                }
                trafficRules.add(trafficRule);
            }
            trafficConfig.setRules(trafficRules);
        }
        return trafficConfig;
    }

    private void validateConclusionStatusTransition(ExperimentMetadata.ConclusionStatus currentStatus,
                                                    ExperimentMetadata.ConclusionStatus targetStatus) {
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "不允许的结论状态流转: " + currentStatus + " -> " + targetStatus
                            + "，允许的下一步: " + currentStatus.allowedTransitions());
        }
    }

    private List<EventDefinition> normalizeEventDefinitions(List<EventDefinition> eventDefinitions) {
        List<EventDefinition> normalizedDefinitions = new ArrayList<>();
        for (EventDefinition eventDefinition : eventDefinitions) {
            EventDefinition normalizedDefinition = new EventDefinition();
            normalizedDefinition.setKey(normalizeDefinitionKey(eventDefinition.getKey(), "事件编码"));
            normalizedDefinition.setLabel(requireTrimmedText(eventDefinition.getLabel(),
                    "事件名称不能为空: " + eventDefinition.getKey()));
            normalizedDefinition.setDescription(trimToNull(eventDefinition.getDescription()));
            normalizedDefinition.setCategory(trimToNull(eventDefinition.getCategory()));
            normalizedDefinition.setPrimary(Boolean.TRUE.equals(eventDefinition.getPrimary()));
            normalizedDefinitions.add(normalizedDefinition);
        }
        return normalizedDefinitions;
    }

    private List<MetricDefinition> normalizeMetricDefinitions(List<MetricDefinition> metricDefinitions) {
        List<MetricDefinition> normalizedDefinitions = new ArrayList<>();
        for (MetricDefinition metricDefinition : metricDefinitions) {
            MetricDefinition normalizedMetric = new MetricDefinition();
            normalizedMetric.setKey(normalizeDefinitionKey(metricDefinition.getKey(), "指标编码"));
            normalizedMetric.setName(requireTrimmedText(metricDefinition.getName(),
                    "指标名称不能为空: " + metricDefinition.getKey()));
            normalizedMetric.setDescription(trimToNull(metricDefinition.getDescription()));
            normalizedMetric.setAggregationType(metricDefinition.getAggregationType());
            normalizedMetric.setNumeratorEventType(trimToNull(metricDefinition.getNumeratorEventType()));
            normalizedMetric.setDenominatorType(metricDefinition.getDenominatorType());
            normalizedMetric.setDenominatorEventType(trimToNull(metricDefinition.getDenominatorEventType()));
            normalizedMetric.setPrimaryMetric(Boolean.TRUE.equals(metricDefinition.getPrimaryMetric()));
            normalizedMetric.setGuardrailMetric(Boolean.TRUE.equals(metricDefinition.getGuardrailMetric()));
            normalizedDefinitions.add(normalizedMetric);
        }
        return normalizedDefinitions;
    }
    
    /**
     * 获取实验列表
     */
    @Override
    public List<Experiment> listExperiments() {
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        experiments.add(metadata.getExperiment());
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            return experiments;
        } catch (Exception e) {
            log.error("获取实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 根据状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return listExperiments();
        }
        
        // 验证状态值
        Experiment.ExperimentStatus targetStatus;
        try {
            targetStatus = Experiment.ExperimentStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                    "不支持的实验状态: " + status + "，支持的状态: DRAFT, RUNNING, PAUSED, STOPPED");
        }
        
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        Experiment experiment = metadata.getExperiment();
                        if (experiment.getStatus() == targetStatus) {
                            experiments.add(experiment);
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            log.info("根据状态[{}]查询实验，共{}个", status, experiments.size());
            return experiments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("根据状态查询实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 根据多个状态查询实验列表
     */
    @Override
    public List<Experiment> listExperimentsByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return listExperiments();
        }
        
        // 验证并转换状态值
        List<Experiment.ExperimentStatus> targetStatuses = new ArrayList<>();
        for (String status : statuses) {
            try {
                targetStatuses.add(Experiment.ExperimentStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                        "不支持的实验状态: " + status + "，支持的状态: DRAFT, RUNNING, PAUSED, STOPPED");
            }
        }
        
        try {
            List<String> experimentIds = configService.getAllExperimentIds();
            List<Experiment> experiments = new ArrayList<>();
            
            for (String experimentId : experimentIds) {
                try {
                    ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
                    if (metadata != null && metadata.getExperiment() != null) {
                        Experiment experiment = metadata.getExperiment();
                        if (targetStatuses.contains(experiment.getStatus())) {
                            experiments.add(experiment);
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取实验失败: {}", experimentId, e);
                }
            }
            
            log.info("根据状态{}查询实验，共{}个", statuses, experiments.size());
            return experiments;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("根据状态查询实验列表失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 删除实验（无用户系统版本）
     */
    @Override
    public void deleteExperiment(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND);
        }
        
        try {
            configService.deleteExperimentConfig(experimentId);
        } catch (Exception e) {
            log.error("删除实验配置失败: {}", experimentId, e);
            throw new BusinessException(ResponseCode.OPERATION_FAILED, "删除实验配置失败: " + e.getMessage());
        }
        
        log.info("删除实验: {}", experimentId);
    }
    
    /**
     * 批量暂停实验
     */
    @Override
    public Map<String, Object> batchPauseExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "pause", experimentId -> executeBatchOperation(experimentId, "暂停", this::pauseExperiment));
    }
    
    /**
     * 批量停止实验
     */
    @Override
    public Map<String, Object> batchStopExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "stop", experimentId -> executeBatchOperation(experimentId, "停止", this::stopExperiment));
    }
    
    /**
     * 批量恢复实验
     */
    @Override
    public Map<String, Object> batchResumeExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "resume", experimentId -> executeBatchOperation(experimentId, "恢复", this::resumeExperiment));
    }
    
    /**
     * 批量删除实验
     */
    @Override
    public Map<String, Object> batchDeleteExperiments(List<String> experimentIds) {
        return batchOperation(experimentIds, "delete", experimentId -> executeBatchOperation(experimentId, "删除", this::deleteExperiment));
    }
    
    /**
     * 批量操作通用方法
     */
    private Map<String, Object> batchOperation(List<String> experimentIds, String operationName,
                                                java.util.function.Function<String, String> operation) {
        Map<String, Object> result = new HashMap<>();
        List<String> successIds = new ArrayList<>();
        List<Map<String, String>> failedItems = new ArrayList<>();
        
        if (experimentIds == null || experimentIds.isEmpty()) {
            result.put("success", false);
            result.put("message", "实验ID列表不能为空");
            return result;
        }
        
        for (String experimentId : experimentIds) {
            String error = operation.apply(experimentId);
            if (error == null) {
                successIds.add(experimentId);
            } else {
                Map<String, String> failedItem = new HashMap<>();
                failedItem.put("id", experimentId);
                failedItem.put("error", error);
                failedItems.add(failedItem);
            }
        }
        
        result.put("success", failedItems.isEmpty());
        result.put("operation", operationName);
        result.put("total", experimentIds.size());
        result.put("successCount", successIds.size());
        result.put("failedCount", failedItems.size());
        result.put("successIds", successIds);
        result.put("failedItems", failedItems);
        
        if (failedItems.isEmpty()) {
            result.put("message", String.format("批量%s成功，共%d个实验", 
                    getOperationLabel(operationName), successIds.size()));
        } else if (successIds.isEmpty()) {
            result.put("message", String.format("批量%s失败，全部%d个实验操作失败", 
                    getOperationLabel(operationName), failedItems.size()));
        } else {
            result.put("message", String.format("批量%s部分成功：%d个成功，%d个失败", 
                    getOperationLabel(operationName), successIds.size(), failedItems.size()));
        }
        
        log.info("批量{}实验: 总数={}, 成功={}, 失败={}", 
                operationName, experimentIds.size(), successIds.size(), failedItems.size());
        
        return result;
    }
    
    private String getOperationLabel(String operation) {
        switch (operation) {
            case "pause": return "暂停";
            case "stop": return "停止";
            case "resume": return "恢复";
            case "delete": return "删除";
            default: return operation;
        }
    }

    private String executeBatchOperation(String experimentId,
                                         String operationLabel,
                                         java.util.function.Consumer<String> operation) {
        try {
            operation.accept(experimentId);
            return null;
        } catch (BusinessException exception) {
            return exception.getMessage();
        } catch (Exception exception) {
            return operationLabel + "失败: " + exception.getMessage();
        }
    }
    
    /**
     * 校验实验创建请求参数
     */
    private void validateExperimentRequest(ExperimentCreateRequest request,
                                           List<GroupConfigFieldDefinition> groupConfigSchema) {
        // 校验实验名称
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称不能为空");
        }
        
        if (request.getName().length() > 100) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验名称长度不能超过100个字符");
        }
        
        // 校验实验组
        if (request.getGroups() == null || request.getGroups().isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置一个实验组");
        }
        
        if (request.getGroups().size() < 2) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要配置2个实验组（基准组和变体组）");
        }
        
        // 校验实验组ID唯一性
        java.util.Set<String> groupIds = new java.util.HashSet<>();
        for (ExperimentCreateRequest.GroupConfig group : request.getGroups()) {
            if (group.getId() == null || group.getId().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID不能为空");
            }
            if (!groupIds.add(group.getId())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组ID重复: " + group.getId());
            }
            if (group.getName() == null || group.getName().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "实验组名称不能为空");
            }
            groupConfigSchemaValidator.normalizeGroupConfig(groupConfigSchema, group.getConfig(), group.getId());
        }
        
        // 校验流量配置
        if (request.getTraffic() != null) {
            ExperimentCreateRequest.TrafficConfigRequest traffic = request.getTraffic();
            
            // 校验总流量
            if (traffic.getTotalTraffic() != null) {
                if (traffic.getTotalTraffic() < 0 || traffic.getTotalTraffic() > 1) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, "总流量比例必须在0-1之间");
                }
            }
            
            // 校验流量分配策略
            if (traffic.getStrategy() != null) {
                try {
                    TrafficConfig.TrafficStrategy.ofOrThrow(traffic.getStrategy());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                            "不支持的流量分配策略: " + traffic.getStrategy() + 
                            "，支持的策略: RANDOM, HASH, RULE, THOMPSON_SAMPLING, UCB");
                }
            }
            
            // 校验流量分配比例
            if (traffic.getAllocation() != null && !traffic.getAllocation().isEmpty()) {
                double totalRatio = 0.0;
                for (ExperimentCreateRequest.GroupAllocationRequest allocation : traffic.getAllocation()) {
                    if (allocation.getGroup() == null || allocation.getGroup().trim().isEmpty()) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, "流量分配的组ID不能为空");
                    }
                    if (!groupIds.contains(allocation.getGroup())) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                                "流量分配的组ID不存在: " + allocation.getGroup());
                    }
                    if (allocation.getRatio() == null || allocation.getRatio() < 0 || allocation.getRatio() > 1) {
                        throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                                "流量分配比例必须在0-1之间: " + allocation.getGroup());
                    }
                    totalRatio += allocation.getRatio();
                }
                
                // 校验流量分配比例总和（允许小误差）
                if (Math.abs(totalRatio - 1.0) > 0.001) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, 
                            String.format("流量分配比例总和必须等于1，当前为%.4f", totalRatio));
                }
            }

            if (traffic.getRuleFallbackStrategy() != null) {
                try {
                    TrafficConfig.RuleFallbackStrategy.ofOrThrow(traffic.getRuleFallbackStrategy());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "不支持的规则回退策略: " + traffic.getRuleFallbackStrategy()
                                    + "，支持的策略: HASH, FIRST_ALLOCATION");
                }
            }

            TrafficConfig trafficConfig = buildTrafficConfig(traffic);
            trafficRuleEvaluator.validateRules(trafficConfig, groupIds);
        }
        
        // 校验时间范围
        if (request.getStartTime() != null && request.getEndTime() != null) {
            if (request.getEndTime().isBefore(request.getStartTime())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "结束时间不能早于开始时间");
            }
        }

        validateEventDefinitions(request.getEventDefinitions());
        validateMetricDefinitions(request.getMetricDefinitions(), request.getEventDefinitions());
    }

    private void validateEventDefinitions(List<EventDefinition> eventDefinitions) {
        if (eventDefinitions == null || eventDefinitions.isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要定义一个事件");
        }

        java.util.Set<String> eventKeys = new java.util.HashSet<>();
        int primaryEventCount = 0;
        for (EventDefinition eventDefinition : eventDefinitions) {
            if (eventDefinition == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件定义不能为空");
            }
            String eventKey = normalizeDefinitionKey(eventDefinition.getKey(), "事件编码");
            if (!eventKeys.add(eventKey)) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件编码重复: " + eventKey);
            }
            if (eventDefinition.getLabel() == null || eventDefinition.getLabel().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "事件名称不能为空: " + eventKey);
            }
            if (Boolean.TRUE.equals(eventDefinition.getPrimary())) {
                primaryEventCount++;
            }
        }

        if (primaryEventCount > 1) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "只能配置一个主事件");
        }
    }

    private void validateMetricDefinitions(List<MetricDefinition> metricDefinitions,
                                           List<EventDefinition> eventDefinitions) {
        if (metricDefinitions == null || metricDefinitions.isEmpty()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "至少需要定义一个指标");
        }

        java.util.Set<String> definedEventKeys = eventDefinitions.stream()
                .map(EventDefinition::getKey)
                .map(this::normalizeDefinitionKey)
                .collect(Collectors.toSet());

        java.util.Set<String> metricKeys = new java.util.HashSet<>();
        int primaryMetricCount = 0;
        for (MetricDefinition metricDefinition : metricDefinitions) {
            if (metricDefinition == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标定义不能为空");
            }
            String metricKey = normalizeDefinitionKey(metricDefinition.getKey(), "指标编码");
            if (!metricKeys.add(metricKey)) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标编码重复: " + metricKey);
            }
            if (metricDefinition.getName() == null || metricDefinition.getName().trim().isEmpty()) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "指标名称不能为空: " + metricKey);
            }
            if (metricDefinition.getAggregationType() == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "指标聚合类型不能为空: " + metricKey);
            }
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.RATE) {
                if (metricDefinition.getDenominatorType() == null) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "RATE 指标必须配置分母类型: " + metricKey);
                }
                if (metricDefinition.getNumeratorEventType() == null
                        || metricDefinition.getNumeratorEventType().trim().isEmpty()) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "RATE 指标必须配置分子事件: " + metricKey);
                }
                validateMetricEventReference(metricDefinition.getNumeratorEventType(), definedEventKeys,
                        "分子事件", metricKey);
                if (metricDefinition.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                        && (metricDefinition.getDenominatorEventType() == null
                        || metricDefinition.getDenominatorEventType().trim().isEmpty())) {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                            "事件型分母必须配置分母事件: " + metricKey);
                }
                if (metricDefinition.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT) {
                    validateMetricEventReference(metricDefinition.getDenominatorEventType(), definedEventKeys,
                            "分母事件", metricKey);
                }
            }
            if (metricDefinition.getAggregationType() == MetricDefinition.AggregationType.COUNT
                    && metricDefinition.getNumeratorEventType() != null
                    && !metricDefinition.getNumeratorEventType().trim().isEmpty()) {
                validateMetricEventReference(metricDefinition.getNumeratorEventType(), definedEventKeys,
                        "指标事件", metricKey);
            }
            if (Boolean.TRUE.equals(metricDefinition.getPrimaryMetric())) {
                primaryMetricCount++;
            }
        }

        if (primaryMetricCount == 0) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "必须配置一个主指标");
        }
        if (primaryMetricCount > 1) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "只能配置一个主指标");
        }
    }

    private void validateMetricEventReference(String eventKey, java.util.Set<String> definedEventKeys,
                                              String role, String metricKey) {
        String normalizedEventKey = normalizeDefinitionKey(eventKey, role);
        if (!definedEventKeys.contains(normalizedEventKey)) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    role + "未在事件定义中声明: " + normalizedEventKey + "，指标: " + metricKey);
        }
    }

    private String normalizeDefinitionKey(String key) {
        return normalizeDefinitionKey(key, "编码");
    }

    private String normalizeDefinitionKey(String key, String fieldLabel) {
        String normalizedKey = requireTrimmedText(key, fieldLabel + "不能为空").toUpperCase();
        if (!DEFINITION_KEY_PATTERN.matcher(normalizedKey).matches()) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                    fieldLabel + "格式不合法，仅支持大写英文、数字和下划线: " + normalizedKey);
        }
        return normalizedKey;
    }

    private String requireTrimmedText(String value, String errorMessage) {
        String normalizedValue = trimToNull(value);
        if (normalizedValue == null) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, errorMessage);
        }
        return normalizedValue;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
