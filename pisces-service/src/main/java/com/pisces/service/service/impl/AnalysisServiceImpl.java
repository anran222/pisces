package com.pisces.service.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.repository.ExperimentReportSnapshotRepository;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.util.StatisticalUtils;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.HTEAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

/**
 * 数据分析服务实现
 */
@Slf4j
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private static final double DEFAULT_GATE_MDE = 0.05;

    private static final double DEFAULT_GATE_ALPHA = 0.05;

    private static final double DEFAULT_GATE_POWER = 0.80;
    
    @Autowired
    private ConfigService configService;
    
    @Autowired
    private DataService dataService;
    
    @Autowired
    private BayesianAnalysisService bayesianAnalysisService;
    
    @Autowired
    private CausalInferenceService causalInferenceService;
    
    @Autowired
    private HTEAnalysisService hteAnalysisService;
    
    @Autowired
    private TongYiConfig tongYiConfig;

    @Autowired
    private ExperimentReportSnapshotRepository experimentReportSnapshotRepository;
    
    /**
     * 获取实验统计数据
     */
    @Override
    public Statistics getStatistics(String experimentId) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        
        // 确定基准组（第一个组为基准组）
        String baselineGroupId = resolveBaselineGroupId(metadata);
        double baselineConversionRate = 0.0;
        
        // 遍历所有实验组计算基础统计
        if (metadata.getGroups() != null) {
            for (Map.Entry<String, com.pisces.common.model.ExperimentGroup> entry : metadata.getGroups().entrySet()) {
                String groupId = entry.getKey();
                com.pisces.common.model.ExperimentGroup group = entry.getValue();
                
                Statistics.GroupStatistics groupStats = calculateGroupStatistics(
                        experimentId, groupId, group, baselineGroupId, metricDefinitions);
                groupStatsMap.put(groupId, groupStats);
                
                // 累计总访客和事件
                totalVisitors += groupStats.getUserCount() != null ? groupStats.getUserCount() : 0;
                totalAssignments += dataService.getAssignmentCount(experimentId, groupId);
                totalExposures += dataService.getExposureCount(experimentId, groupId);
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
    
    /**
     * 计算实验组统计数据
     */
    private Statistics.GroupStatistics calculateGroupStatistics(String experimentId, String groupId,
                                                                  com.pisces.common.model.ExperimentGroup group,
                                                                  String baselineGroupId,
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
        String viewType = Event.EventType.VIEW.name();
        String clickType = Event.EventType.CLICK.name();
        String convertType = Event.EventType.CONVERT.name();
        
        long viewCount = dataService.getEventCount(experimentId, groupId, viewType);
        long clickCount = dataService.getEventCount(experimentId, groupId, clickType);
        long convertCount = dataService.getEventCount(experimentId, groupId, convertType);
        
        eventCounts.put(viewType, viewCount);
        eventCounts.put(clickType, clickCount);
        eventCounts.put(convertType, convertCount);
        
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        
        comparison.put("baseline", baselineGroup);
        comparison.put("baselineStats", baseline);
        comparison.put("dataQualityCheck", statistics.getDataQualityCheck());
        
        // 对比其他组
        Map<String, Map<String, Object>> comparisons = new HashMap<>();
        for (Map.Entry<String, Statistics.GroupStatistics> entry : groupStats.entrySet()) {
            if (!entry.getKey().equals(baselineGroup)) {
                Statistics.GroupStatistics target = entry.getValue();
                if (target != null) {
                    Map<String, Object> comp = compareWithBaseline(baseline, target);
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
                                                    Statistics.GroupStatistics target) {
        Map<String, Object> comparison = new HashMap<>();
        
        // 转化率对比
        Double baselineRateObj = baseline.getConversionRate();
        Double targetRateObj = target.getConversionRate();
        double baselineRate = baselineRateObj != null ? baselineRateObj : 0.0;
        double targetRate = targetRateObj != null ? targetRateObj : 0.0;
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        return bayesianAnalysisService.getBayesianAnalysis(experimentId);
    }
    
    @Override
    public Map<String, Object> shouldEarlyStop(String experimentId, String variantGroupId, 
                                              String baselineGroupId, Double winRateThreshold) {
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
            case "CAUSAL_FOREST":
                @SuppressWarnings("unchecked")
                java.util.List<String> features2 = (java.util.List<String>) params.get("userFeatures");
                result = causalInferenceService.analyzeByCausalForest(experimentId, treatmentGroupId, controlGroupId, features2);
                break;
            default:
                throw new IllegalArgumentException("不支持的因果推断方法: " + method);
        }
        if (!isBlockedResult(result)) {
            attachDataQualityCheck(result, statistics);
        }
        return result;
    }

    @Override
    public Map<String, Object> analyzeHTE(String experimentId, String treatmentGroupId,
                                           String controlGroupId, java.util.List<String> userFeatures) {
        Statistics statistics = getStatistics(experimentId);
        Map<String, Object> gateResult = buildAnalysisGateResult("HTE", "HTE", statistics);
        if (gateResult != null) {
            return gateResult;
        }
        Map<String, Object> contractResult = validateFeatureContract("HTE", userFeatures);
        if (contractResult != null) {
            return contractResult;
        }
        Map<String, Object> result = hteAnalysisService.analyzeHTE(experimentId, treatmentGroupId, controlGroupId, userFeatures);
        if (!isBlockedResult(result)) {
            attachDataQualityCheck(result, statistics);
        }
        return result;
    }
    
    @Override
    public Map<String, Object> identifySensitiveGroups(String experimentId, String treatmentGroupId,
                                                       String controlGroupId, java.util.List<String> userFeatures) {
        Statistics statistics = getStatistics(experimentId);
        Map<String, Object> gateResult = buildAnalysisGateResult("HTE", "IDENTIFY_SENSITIVE_GROUPS", statistics);
        if (gateResult != null) {
            return gateResult;
        }
        Map<String, Object> contractResult = validateFeatureContract("IDENTIFY_SENSITIVE_GROUPS", userFeatures);
        if (contractResult != null) {
            return contractResult;
        }
        Map<String, Object> result = hteAnalysisService.identifySensitiveGroups(experimentId, treatmentGroupId, controlGroupId, userFeatures);
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
        
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
                    groupValues.put(groupId, calculateTimelineMetric(events, cursor, bucketUnit, metricType));
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
                                           String metricType) {
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

        long views = bucketEvents.stream().filter(event -> event.getEventType() == Event.EventType.VIEW).count();
        long clicks = bucketEvents.stream().filter(event -> event.getEventType() == Event.EventType.CLICK).count();
        long conversions = bucketEvents.stream().filter(event -> event.getEventType() == Event.EventType.CONVERT).count();

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
    
    @Override
    public Map<String, Object> getAIInsights(String experimentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        
        try {
            // 获取实验数据
            ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
     * 基于实际数据生成具体的分析报告
     */
    private String generateDataDrivenAnalysis(ExperimentMetadata metadata, Statistics statistics,
                                               Map<String, Object> bayesianAnalysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## AI智能分析报告\n\n");
        
        // 1. 数据质量评估
        sb.append("### 1. 数据质量评估\n");
        long totalVisitors = 0;
        if (statistics != null && statistics.getSummary() != null) {
            Long visitors = statistics.getSummary().getTotalVisitors();
            totalVisitors = visitors != null ? visitors : 0;
        }
        
        if (totalVisitors < 100) {
            sb.append("⚠️ **数据量严重不足**：当前仅有 ").append(totalVisitors).append(" 位访客，")
              .append("统计结果不可靠。建议至少收集 1,000 位访客数据后再做分析。\n\n");
        } else if (totalVisitors < 500) {
            sb.append("⚠️ **数据量偏少**：当前有 ").append(totalVisitors).append(" 位访客，")
              .append("结论可能不够稳定。建议继续收集数据至少达到 1,000 位访客。\n\n");
        } else if (totalVisitors < 1000) {
            sb.append("📊 **数据量适中**：当前有 ").append(totalVisitors).append(" 位访客，")
              .append("初步结论具有一定参考价值。建议继续观察 2-3 天以确保结果稳定。\n\n");
        } else {
            sb.append("✅ **数据量充足**：当前有 ").append(totalVisitors).append(" 位访客，")
              .append("统计结果具有较高可信度。\n\n");
        }
        
        // 2. 效果分析
        sb.append("### 2. 效果分析\n");
        String bestGroup = null;
        double bestRate = 0.0;
        String baselineGroup = null;
        double baselineRate = 0.0;
        
        if (statistics != null && statistics.getGroupStatistics() != null) {
            boolean isFirst = true;
            for (Map.Entry<String, Statistics.GroupStatistics> entry : statistics.getGroupStatistics().entrySet()) {
                Statistics.GroupStatistics gs = entry.getValue();
                double rate = gs.getConversionRate() != null ? gs.getConversionRate() : 0.0;
                
                if (isFirst || Boolean.TRUE.equals(gs.getIsBaseline())) {
                    baselineGroup = entry.getKey();
                    baselineRate = rate;
                    isFirst = false;
                }
                
                if (rate > bestRate) {
                    bestRate = rate;
                    bestGroup = entry.getKey();
                }
                
                sb.append("- **").append(gs.getGroupName() != null ? gs.getGroupName() : entry.getKey()).append("**：")
                  .append("转化率 ").append(String.format("%.2f%%", rate * 100));
                if (gs.getLiftRate() != null && !Boolean.TRUE.equals(gs.getIsBaseline())) {
                    double lift = gs.getLiftRate();
                    sb.append("（相对基准").append(lift >= 0 ? "提升" : "下降")
                      .append(" ").append(String.format("%.2f%%", Math.abs(lift) * 100)).append("）");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
        
        if (bestGroup != null && !bestGroup.equals(baselineGroup)) {
            double lift = (bestRate - baselineRate) / baselineRate;
            sb.append("📈 **最佳表现**：**").append(bestGroup).append("** 组转化率最高，")
              .append("达到 ").append(String.format("%.2f%%", bestRate * 100))
              .append("，相对基准组提升 ").append(String.format("%.1f%%", lift * 100)).append("。\n\n");
        } else if (bestGroup != null) {
            sb.append("📊 **当前状态**：基准组 **").append(bestGroup).append("** 表现最好，")
              .append("其他变体尚未展现出明显优势。\n\n");
        }
        
        // 3. 统计可信度
        sb.append("### 3. 统计可信度\n");
        double maxWinRate = 0.0;
        String leadingVariant = null;
        
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                if (entry.getValue() > maxWinRate) {
                    maxWinRate = entry.getValue();
                    leadingVariant = entry.getKey();
                }
            }
        }
        
        if (maxWinRate >= 0.95) {
            sb.append("✅ **高置信度**：**").append(leadingVariant).append("** 的胜率达到 ")
              .append(String.format("%.1f%%", maxWinRate * 100))
              .append("，已超过95%显著性阈值，可以做出决策。\n\n");
        } else if (maxWinRate >= 0.80) {
            sb.append("🔶 **中等置信度**：领先变体 **").append(leadingVariant).append("** 的胜率为 ")
              .append(String.format("%.1f%%", maxWinRate * 100))
              .append("，接近但未达到95%阈值。建议继续收集数据。\n\n");
        } else {
            sb.append("⚠️ **置信度较低**：当前没有明显的领先变体，最高胜率仅为 ")
              .append(String.format("%.1f%%", maxWinRate * 100))
              .append("。需要更多数据才能得出可靠结论。\n\n");
        }
        
        // 4. 风险评估
        sb.append("### 4. 风险评估\n");
        if (maxWinRate >= 0.95 && totalVisitors >= 1000) {
            sb.append("🟢 **低风险**：数据量充足，统计显著，全量发布风险较低。\n\n");
        } else if (maxWinRate >= 0.80 && totalVisitors >= 500) {
            sb.append("🟡 **中等风险**：建议先进行50%灰度发布，观察3-5天后再决定是否全量。\n\n");
        } else {
            sb.append("🔴 **高风险**：当前数据不足以支持决策，贸然上线可能导致负面影响。\n\n");
        }
        
        // 5. 具体建议
        sb.append("### 5. 具体建议\n");
        List<String> suggestions = new ArrayList<>();
        
        if (totalVisitors < 1000) {
            suggestions.add("继续收集数据，目标至少达到 1,000 位访客/组");
        }
        
        if (maxWinRate >= 0.95 && totalVisitors >= 1000) {
            suggestions.add("可以将最佳变体 **" + leadingVariant + "** 全量发布");
            suggestions.add("发布后持续监控核心指标1周");
            suggestions.add("准备回滚方案以防意外");
        } else if (maxWinRate >= 0.80) {
            suggestions.add("考虑将领先变体流量比例提升至50%");
            suggestions.add("设置更长的观察期（至少7天）");
            suggestions.add("关注用户留存等长期指标");
        } else {
            suggestions.add("保持当前流量分配，继续实验");
            suggestions.add("检查实验设计是否合理");
            suggestions.add("考虑增加变体的差异化程度");
        }
        
        suggestions.add("定期查看数据，关注异常波动");
        
        for (int i = 0; i < suggestions.size(); i++) {
            sb.append(i + 1).append(". ").append(suggestions.get(i)).append("\n");
        }
        sb.append("\n");
        
        // 6. 预计影响
        sb.append("### 6. 预计影响\n");
        if (bestGroup != null && !bestGroup.equals(baselineGroup) && baselineRate > 0) {
            double expectedLift = (bestRate - baselineRate) / baselineRate;
            sb.append("如果采用最佳方案 **").append(bestGroup).append("** 全量上线：\n");
            sb.append("- 预计转化率提升：**").append(String.format("%.1f%%", expectedLift * 100)).append("**\n");
            sb.append("- 按当前日均 ").append(totalVisitors > 0 ? totalVisitors / Math.max(1, 
                    ChronoUnit.DAYS.between(metadata.getExperiment().getStartTime() != null ? 
                    metadata.getExperiment().getStartTime() : LocalDateTime.now().minusDays(1), 
                    LocalDateTime.now())) : 100).append(" 访客计算\n");
            sb.append("- 每月可额外带来约 ").append(String.format("%.0f", expectedLift * totalVisitors * 30 * baselineRate))
              .append(" 次转化\n");
        } else {
            sb.append("当前实验尚未产生明显的正向效果，建议优化实验方案后重新测试。\n");
        }
        
        return sb.toString();
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
        log.info("超时设置: {} 毫秒", tongYiConfig.getTimeout());
        log.info("Prompt长度: {} 字符", prompt != null ? prompt.length() : 0);
        log.info("=====================================");
        
        ensureTongYiAvailableForAnalysis();
        
        long startTime = System.currentTimeMillis();
        
        // 打印Prompt内容（前500字符）
        if (prompt != null && prompt.length() > 0) {
            String promptPreview = prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt;
            log.info("Prompt预览:\n{}", promptPreview);
        }
        
        // 使用 CompletableFuture 添加超时保护
        try {
            java.util.concurrent.CompletableFuture<String> future = 
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    log.info("正在创建Generation实例...");
                    Generation gen = new Generation();
                    
                    Message systemMsg = Message.builder()
                            .role(Role.SYSTEM.getValue())
                            .content("你是一位资深的A/B测试数据分析专家，擅长从实验数据中提取洞察并给出专业建议。")
                            .build();
                    
                    Message userMsg = Message.builder()
                            .role(Role.USER.getValue())
                            .content(prompt)
                            .build();
                    
                    log.info("正在构建GenerationParam...");
                    GenerationParam param = GenerationParam.builder()
                            .apiKey(tongYiConfig.getApiKey())
                            .model(tongYiConfig.getModel())
                            .messages(Arrays.asList(systemMsg, userMsg))
                            .resultFormat("text")
                            .build();
                    
                    log.info("正在发送请求到通义API（{}）...", tongYiConfig.getModel());
                    long apiStartTime = System.currentTimeMillis();
                    
                    GenerationResult result = gen.call(param);
                    
                    long apiElapsed = System.currentTimeMillis() - apiStartTime;
                    log.info("通义API响应完成，耗时: {} 毫秒", apiElapsed);
                    
                    if (result == null) {
                        log.warn("通义API返回null");
                        return null;
                    }
                    
                    if (result.getOutput() == null) {
                        log.warn("通义API返回的output为null，requestId={}", result.getRequestId());
                        return null;
                    }
                    
                    String text = result.getOutput().getText();
                    if (StringUtils.hasText(text)) {
                        log.info("通义API返回成功，requestId={}，响应长度={} 字符", 
                                result.getRequestId(), text.length());
                        // 打印响应预览
                        String responsePreview = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                        log.info("响应预览:\n{}", responsePreview);
                        return text;
                    }
                    
                    log.warn("通义API返回空文本，requestId={}", result.getRequestId());
                    return null;
                    
                } catch (com.alibaba.dashscope.exception.ApiException e) {
                    log.error("通义API异常 - 状态: {}, 错误信息: {}", 
                            e.getStatus(), e.getMessage());
                    return null;
                } catch (Exception e) {
                    log.error("调用通义API发生异常: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                    e.printStackTrace();
                    return null;
                }
            });
            
            // 设置5分钟（300秒）超时
            log.info("等待通义API响应，超时时间: 5分钟...");
            String result = future.get(300, java.util.concurrent.TimeUnit.SECONDS);
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (result != null) {
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
        ensureTongYiAvailableForAnalysis();
        
        try {
            Generation gen = new Generation();
            
            Message systemMsg = Message.builder()
                    .role(Role.SYSTEM.getValue())
                    .content("你是一位资深的A/B测试实验设计专家，擅长设计科学严谨的实验方案。")
                    .build();
            
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();
            
            GenerationParam param = GenerationParam.builder()
                    .apiKey(tongYiConfig.getApiKey())
                    .model(tongYiConfig.getModel())
                    .messages(Arrays.asList(systemMsg, userMsg))
                    .resultFormat("text")
                    .build();
            
            GenerationResult result = gen.call(param);
            
            if (result != null && result.getOutput() != null && 
                StringUtils.hasText(result.getOutput().getText())) {
                return result.getOutput().getText();
            }
            
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI实验设计返回空结果");
            
        } catch (Exception e) {
            log.error("调用通义API进行实验设计失败", e);
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI实验设计失败: " + e.getMessage());
        }
    }

    private void ensureTongYiAvailableForAnalysis() {
        if (!tongYiConfig.isEnabled()) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "通义AI未启用，无法执行真实AI流程");
        }
        if (!StringUtils.hasText(tongYiConfig.getApiKey())) {
            throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE, "未配置 TONGYI_API_KEY，无法执行真实AI流程");
        }
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
    
    /**
     * 生成默认实验设计（后备方案）
     */
    private Map<String, Object> generateDefaultExperimentDesign(String businessScenario, String targetMetric) {
        Map<String, Object> design = new HashMap<>();
        design.put("hypothesis", "通过优化可以提升" + targetMetric);
        design.put("recommendedGroups", 3);
        design.put("recommendedDuration", "2周");
        design.put("minimumSamplePerGroup", 1000);
        return design;
    }
    
    @Override
    public Map<String, Object> autoGraduateDecision(String experimentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        
        try {
            ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
            if (metadata == null) {
                result.put("error", "实验不存在");
                result.put("canGraduate", false);
                return result;
            }
            
            Statistics statistics = getStatistics(experimentId);
            Map<String, Object> bayesianAnalysis = getBayesianAnalysis(experimentId);
            
            // 评估是否可以毕业
            GraduationDecision decision = evaluateGraduationReadiness(metadata, statistics, bayesianAnalysis);
            result.put("dataQualityCheck", statistics != null ? statistics.getDataQualityCheck() : null);
            
            result.put("canGraduate", decision.canGraduate);
            result.put("recommendedVariant", decision.recommendedVariant);
            result.put("confidence", decision.confidence);
            result.put("riskLevel", decision.riskLevel);
            result.put("reasons", decision.reasons);
            result.put("warnings", decision.warnings);
            
            // 如果可以毕业，生成毕业计划
            if (decision.canGraduate) {
                Map<String, Object> graduationPlan = generateGraduationPlan(decision);
                result.put("graduationPlan", graduationPlan);
            } else {
                // 生成继续实验的建议
                Map<String, Object> continueAdvice = generateContinueAdvice(decision);
                result.put("continueAdvice", continueAdvice);
            }
            
            result.put("evaluatedAt", LocalDateTime.now());
            result.put("success", true);
            
        } catch (Exception e) {
            log.error("自动毕业决策失败", e);
            result.put("error", "决策失败: " + e.getMessage());
            result.put("canGraduate", false);
            result.put("success", false);
        }
        
        return result;
    }
    
    /**
     * 毕业决策结果
     */
    private static class GraduationDecision {
        boolean canGraduate;
        String recommendedVariant;
        double confidence;
        String riskLevel; // LOW, MEDIUM, HIGH
        List<String> reasons;
        List<String> warnings;
        
        GraduationDecision() {
            this.reasons = new ArrayList<>();
            this.warnings = new ArrayList<>();
        }
    }
    
    /**
     * 评估是否可以毕业
     */
    private GraduationDecision evaluateGraduationReadiness(ExperimentMetadata metadata,
                                                            Statistics statistics,
                                                            Map<String, Object> bayesianAnalysis) {
        GraduationDecision decision = new GraduationDecision();
        Statistics.DataQualityCheck dataQualityCheck = statistics != null ? statistics.getDataQualityCheck() : null;
        if (dataQualityCheck != null && Boolean.FALSE.equals(dataQualityCheck.getAnalysisReady())) {
            decision.canGraduate = false;
            decision.riskLevel = "HIGH";
            if (dataQualityCheck.getBlockingIssues() != null) {
                decision.reasons.addAll(dataQualityCheck.getBlockingIssues());
            }
            if (dataQualityCheck.getWarnings() != null) {
                decision.warnings.addAll(dataQualityCheck.getWarnings());
            }
            decision.reasons.add("数据质量门禁未通过，当前不允许自动毕业");
            return decision;
        }
        if (statistics != null && statistics.getSummary() != null
                && statistics.getSummary().getBreachedGuardrails() != null
                && !statistics.getSummary().getBreachedGuardrails().isEmpty()) {
            decision.canGraduate = false;
            decision.riskLevel = "HIGH";
            decision.reasons.addAll(statistics.getSummary().getBreachedGuardrails());
            decision.reasons.add("护栏指标未通过，当前不允许自动毕业");
            return decision;
        }
        
        // 检查样本量
        long totalVisitors = 0;
        if (statistics != null && statistics.getSummary() != null) {
            Long visitors = statistics.getSummary().getTotalVisitors();
            totalVisitors = visitors != null ? visitors : 0;
        }
        
        if (totalVisitors < 100) {
            decision.canGraduate = false;
            decision.riskLevel = "HIGH";
            decision.reasons.add("样本量严重不足（< 100），无法做出可靠决策");
            return decision;
        }
        
        if (totalVisitors < 1000) {
            decision.warnings.add("样本量较小（< 1000），结果可能不够稳定");
        }
        
        // 检查贝叶斯胜率
        String bestVariant = null;
        double bestWinRate = 0.0;
        
        if (bayesianAnalysis != null && bayesianAnalysis.containsKey("winRates")) {
            @SuppressWarnings("unchecked")
            Map<String, Double> winRates = (Map<String, Double>) bayesianAnalysis.get("winRates");
            for (Map.Entry<String, Double> entry : winRates.entrySet()) {
                if (entry.getValue() > bestWinRate) {
                    bestWinRate = entry.getValue();
                    bestVariant = entry.getKey();
                }
            }
        }
        
        decision.recommendedVariant = bestVariant;
        decision.confidence = bestWinRate;
        
        // 基于胜率做决策
        if (bestWinRate >= 0.95) {
            decision.canGraduate = true;
            decision.riskLevel = "LOW";
            decision.reasons.add("胜率达到95%，统计显著性充分");
            decision.reasons.add("变体 " + bestVariant + " 表现显著优于其他组");
        } else if (bestWinRate >= 0.90) {
            decision.canGraduate = true;
            decision.riskLevel = "MEDIUM";
            decision.reasons.add("胜率达到90%，接近显著性阈值");
            decision.warnings.add("建议灰度发布，观察一周后全量");
        } else if (bestWinRate >= 0.80) {
            decision.canGraduate = false;
            decision.riskLevel = "MEDIUM";
            decision.reasons.add("胜率为" + String.format("%.1f%%", bestWinRate * 100) + "，尚未达到显著性阈值");
            decision.reasons.add("建议继续收集数据");
        } else {
            decision.canGraduate = false;
            decision.riskLevel = "HIGH";
            decision.reasons.add("各变体之间差异不明显，需要更多数据");
        }
        
        // 检查实验运行时间
        LocalDateTime startTime = metadata.getExperiment().getStartTime();
        if (startTime != null) {
            long daysSinceStart = ChronoUnit.DAYS.between(startTime, LocalDateTime.now());
            if (daysSinceStart < 7) {
                decision.warnings.add("实验运行不足7天，可能存在周期性偏差");
            }
        }
        
        return decision;
    }
    
    /**
     * 生成毕业计划
     */
    private Map<String, Object> generateGraduationPlan(GraduationDecision decision) {
        Map<String, Object> plan = new HashMap<>();
        
        plan.put("recommendedVariant", decision.recommendedVariant);
        plan.put("confidence", decision.confidence);
        
        List<Map<String, Object>> steps = new ArrayList<>();
        
        if ("LOW".equals(decision.riskLevel)) {
            Map<String, Object> step1 = new HashMap<>();
            step1.put("step", 1);
            step1.put("action", "直接全量发布");
            step1.put("description", "将变体 " + decision.recommendedVariant + " 设置为100%流量");
            steps.add(step1);
        } else {
            Map<String, Object> step1 = new HashMap<>();
            step1.put("step", 1);
            step1.put("action", "灰度发布50%");
            step1.put("description", "先将变体 " + decision.recommendedVariant + " 流量提升至50%");
            steps.add(step1);
            
            Map<String, Object> step2 = new HashMap<>();
            step2.put("step", 2);
            step2.put("action", "观察3天");
            step2.put("description", "监控关键指标是否稳定");
            steps.add(step2);
            
            Map<String, Object> step3 = new HashMap<>();
            step3.put("step", 3);
            step3.put("action", "全量发布100%");
            step3.put("description", "确认无异常后全量发布");
            steps.add(step3);
        }
        
        plan.put("steps", steps);
        plan.put("estimatedImpact", "预计转化率提升 " + 
                String.format("%.1f%%", (decision.confidence - 0.5) * 20));
        
        return plan;
    }
    
    /**
     * 生成继续实验的建议
     */
    private Map<String, Object> generateContinueAdvice(GraduationDecision decision) {
        Map<String, Object> advice = new HashMap<>();
        
        advice.put("currentBestVariant", decision.recommendedVariant);
        advice.put("currentConfidence", decision.confidence);
        
        List<String> recommendations = new ArrayList<>();
        recommendations.add("继续收集数据，目标样本量≥1000/组");
        recommendations.add("确保实验运行至少覆盖完整业务周期（7天以上）");
        
        if (decision.confidence >= 0.70) {
            recommendations.add("考虑增加领先变体的流量比例以加速实验");
        }
        
        advice.put("recommendations", recommendations);
        advice.put("estimatedTimeToDecision", "预计还需 " + 
                Math.max(3, (int)((0.95 - decision.confidence) * 30)) + " 天");
        
        return advice;
    }
    
    @Override
    public Map<String, Object> predictExperimentCompletion(String experimentId) {
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        
        try {
            ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
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
        } else if ("PSM".equals(normalizedMethod) || "CAUSAL_FOREST".equals(normalizedMethod)) {
            Object userFeaturesObject = safeParams.get("userFeatures");
            if (!(userFeaturesObject instanceof List)) {
                Map<String, Object> contract = new LinkedHashMap<>();
                contract.put("requiredInputs", Collections.singletonList("userFeatures"));
                contract.put("supportedCovariates", Arrays.asList("viewCount", "clickCount", "eventCount", "rank"));
                contract.put("providedInputs", safeParams.keySet());
                return buildBlockedAnalysisResult("CAUSAL_INFERENCE", method,
                        "PSM / 因果森林需要显式协变量输入，当前请求无效",
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
                        "PSM / 因果森林需要显式协变量输入，当前请求无效",
                        Collections.singletonList("userFeatures 必须是非空列表"),
                        Collections.emptyList(),
                        contract,
                        null);
            }
        }
        return null;
    }

    private Map<String, Object> validateFeatureContract(String analysisType, List<String> userFeatures) {
        if (userFeatures == null || userFeatures.isEmpty()) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("requiredInputs", Collections.singletonList("userFeatures"));
            contract.put("supportedCovariates", Arrays.asList("viewCount", "clickCount", "eventCount", "rank"));
            return buildBlockedAnalysisResult(analysisType, analysisType,
                    "当前分析接口需要显式协变量输入",
                    Collections.singletonList("userFeatures 不能为空"),
                    Collections.emptyList(),
                    contract,
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
