package com.pisces.service.service.impl;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.Statistics;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.config.TongYiConfig;
import com.pisces.service.exception.BusinessException;
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
        
        Map<String, Statistics.GroupStatistics> groupStatsMap = new HashMap<>();
        
        // 用于计算总览的变量
        long totalVisitors = 0;
        long totalEvents = 0;
        double bestConversionRate = 0.0;
        String bestPerformingGroup = null;
        
        // 确定基准组（第一个组为基准组）
        String baselineGroupId = metadata.getGroups() != null && !metadata.getGroups().isEmpty() ?
                metadata.getGroups().keySet().iterator().next() : null;
        double baselineConversionRate = 0.0;
        
        // 遍历所有实验组计算基础统计
        if (metadata.getGroups() != null) {
            for (Map.Entry<String, com.pisces.common.model.ExperimentGroup> entry : metadata.getGroups().entrySet()) {
                String groupId = entry.getKey();
                com.pisces.common.model.ExperimentGroup group = entry.getValue();
                
                Statistics.GroupStatistics groupStats = calculateGroupStatistics(
                        experimentId, groupId, group, baselineGroupId);
                groupStatsMap.put(groupId, groupStats);
                
                // 累计总访客和事件
                totalVisitors += groupStats.getUserCount() != null ? groupStats.getUserCount() : 0;
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
        summary.setBestPerformingGroup(bestPerformingGroup);
        summary.setBestConversionRate(bestConversionRate);
        
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
        
        return statistics;
    }
    
    /**
     * 计算实验组统计数据
     */
    private Statistics.GroupStatistics calculateGroupStatistics(String experimentId, String groupId,
                                                                  com.pisces.common.model.ExperimentGroup group,
                                                                  String baselineGroupId) {
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
        
        // 计算点击率和转化率
        double clickRate = viewCount > 0 ? (double) clickCount / viewCount : 0.0;
        double conversionRate = viewCount > 0 ? (double) convertCount / viewCount : 0.0;
        groupStats.setClickRate(clickRate);
        groupStats.setConversionRate(conversionRate);
        
        // 注意：Statistics.GroupStatistics中的userCount字段实际存储的是visitorCount
        groupStats.setUserCount(visitorCount);
        
        return groupStats;
    }
    
    /**
     * 对比实验组
     */
    @Override
    public Map<String, Object> compareGroups(String experimentId) {
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
        String baselineGroup = groupStats.keySet().iterator().next();
        Statistics.GroupStatistics baseline = groupStats.get(baselineGroup);
        
        if (baseline == null) {
            comparison.put("error", "基准组统计数据为空");
            return comparison;
        }
        
        comparison.put("baseline", baselineGroup);
        comparison.put("baselineStats", baseline);
        
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
        
        // 获取两组数据
        long variantViews = dataService.getEventCount(experimentId, variantGroupId, "VIEW");
        long variantConverts = dataService.getEventCount(experimentId, variantGroupId, "CONVERT");
        long baselineViews = dataService.getEventCount(experimentId, baselineGroupId, "VIEW");
        long baselineConverts = dataService.getEventCount(experimentId, baselineGroupId, "CONVERT");
        
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
        
        // 样本数据
        Map<String, Object> variantData = new HashMap<>();
        variantData.put("views", variantViews);
        variantData.put("conversions", variantConverts);
        variantData.put("conversionRate", variantRate);
        result.put("variantData", variantData);
        
        Map<String, Object> baselineData = new HashMap<>();
        baselineData.put("views", baselineViews);
        baselineData.put("conversions", baselineConverts);
        baselineData.put("conversionRate", baselineRate);
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
        result.put("conclusion", conclusion);
        
        return result;
    }
    
    @Override
    public Map<String, Object> calculateSampleSize(Double baselineRate, Double minimumDetectableEffect,
                                                   Double power, Double significance) {
        double p1 = baselineRate != null ? baselineRate : 0.10; // 默认基准转化率10%
        double mde = minimumDetectableEffect != null ? minimumDetectableEffect : 0.10; // 默认最小可检测效应10%
        double powerLevel = power != null ? power : 0.80; // 默认功效80%
        double alpha = significance != null ? significance : 0.05; // 默认显著性水平5%
        
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
        return bayesianAnalysisService.shouldEarlyStop(experimentId, variantGroupId, 
                baselineGroupId, threshold);
    }
    
    @Override
    public Map<String, Object> causalInference(String experimentId, String treatmentGroupId,
                                              String controlGroupId, String method,
                                              Map<String, Object> params) {
        switch (method.toUpperCase()) {
            case "DID":
                String beforeStart = (String) params.get("beforePeriodStart");
                String beforeEnd = (String) params.get("beforePeriodEnd");
                String afterStart = (String) params.get("afterPeriodStart");
                String afterEnd = (String) params.get("afterPeriodEnd");
                return causalInferenceService.analyzeByDID(experimentId, treatmentGroupId, controlGroupId,
                        beforeStart, beforeEnd, afterStart, afterEnd);
            case "PSM":
                @SuppressWarnings("unchecked")
                java.util.List<String> features = (java.util.List<String>) params.get("userFeatures");
                return causalInferenceService.analyzeByPSM(experimentId, treatmentGroupId, controlGroupId, features);
            case "CAUSAL_FOREST":
                @SuppressWarnings("unchecked")
                java.util.List<String> features2 = (java.util.List<String>) params.get("userFeatures");
                return causalInferenceService.analyzeByCausalForest(experimentId, treatmentGroupId, controlGroupId, features2);
            default:
                throw new IllegalArgumentException("不支持的因果推断方法: " + method);
        }
    }
    
    @Override
    public Map<String, Object> analyzeHTE(String experimentId, String treatmentGroupId,
                                           String controlGroupId, java.util.List<String> userFeatures) {
        return hteAnalysisService.analyzeHTE(experimentId, treatmentGroupId, controlGroupId, userFeatures);
    }
    
    @Override
    public Map<String, Object> identifySensitiveGroups(String experimentId, String treatmentGroupId,
                                                       String controlGroupId, java.util.List<String> userFeatures) {
        return hteAnalysisService.identifySensitiveGroups(experimentId, treatmentGroupId, controlGroupId, userFeatures);
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
        
        // 生成结论和建议
        Map<String, Object> conclusions = generateConclusions(experimentId, statistics, bayesianAnalysis);
        report.put("conclusions", conclusions);
        
        // 报告元数据
        report.put("reportGeneratedAt", java.time.LocalDateTime.now());
        report.put("reportVersion", "1.0");
        
        return report;
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
        String sampleSizeStatus;
        if (totalVisitors == null || totalVisitors < 100) {
            sampleSizeStatus = "样本量不足（< 100），结果可能不可靠";
        } else if (totalVisitors < 1000) {
            sampleSizeStatus = "样本量较小（100-1000），建议继续收集数据";
        } else {
            sampleSizeStatus = "样本量充足（> 1000），结果较为可靠";
        }
        conclusions.put("sampleSizeStatus", sampleSizeStatus);
        conclusions.put("totalVisitors", totalVisitors);
        
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
            conclusions.put("recommendation", recommendation);
        } else {
            conclusions.put("recommendation", "需要收集更多数据才能给出可靠建议");
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
            summary.put("overallConversionRate", expSummary.getOverallConversionRate());
            summary.put("bestPerformingGroup", expSummary.getBestPerformingGroup());
            summary.put("bestConversionRate", expSummary.getBestConversionRate());
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
        
        return summary;
    }
    
    /**
     * 生成可操作的建议列表
     */
    private List<Map<String, Object>> generateActionableRecommendations(ExperimentMetadata metadata,
                                                                          Statistics statistics,
                                                                          Map<String, Object> bayesianAnalysis) {
        List<Map<String, Object>> recommendations = new ArrayList<>();
        
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
        monitorRec.put("description", "定期查看转化率趋势和用户行为数据");
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
            observed[i] = dataService.getVisitorCount(experimentId, gid);
            com.pisces.common.model.ExperimentGroup g = metadata.getGroups().get(gid);
            expectedRatios[i] = g != null && g.getTrafficRatio() != null
                    ? g.getTrafficRatio() : 1.0 / groupIds.size();
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
        double effectSize = mde   != null ? mde   : 0.05;
        double alphaVal   = alpha != null ? alpha : 0.05;
        double betaVal    = beta  != null ? beta  : 0.20;

        long n1 = dataService.getVisitorCount(experimentId, variantGroupId);
        long x1 = dataService.getEventCount(experimentId, variantGroupId, "CONVERT");
        long n2 = dataService.getVisitorCount(experimentId, baselineGroupId);
        long x2 = dataService.getEventCount(experimentId, baselineGroupId, "CONVERT");

        double p0 = n2 > 0 ? (double) x2 / n2 : 0.05;

        Map<String, Object> result = StatisticalUtils.sprtTest(n1, x1, n2, x2, p0, effectSize, alphaVal, betaVal);
        result.put("experimentId", experimentId);
        result.put("variantGroupId", variantGroupId);
        result.put("baselineGroupId", baselineGroupId);
        result.put("variantSampleSize", n1);
        result.put("baselineSampleSize", n2);
        return result;
    }
}
