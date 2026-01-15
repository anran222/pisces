package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.Statistics;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.BayesianAnalysisService;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.HTEAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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
        double pValue = 2 * (1 - normalCDF(Math.abs(zStat)));
        
        // 获取Z临界值
        double zCritical = getZCritical(confidence);
        
        // 计算置信区间
        double marginOfError = zCritical * se;
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
        
        double p2 = p1 * (1 + mde); // 期望转化率
        
        // 获取Z临界值
        double zAlpha = getZCritical(1 - alpha / 2); // 双尾检验
        double zBeta = getZCritical(powerLevel);
        
        // 样本量计算公式（基于两比例检验）
        double pooledP = (p1 + p2) / 2;
        double numerator = Math.pow(zAlpha * Math.sqrt(2 * pooledP * (1 - pooledP)) + 
                                    zBeta * Math.sqrt(p1 * (1 - p1) + p2 * (1 - p2)), 2);
        double denominator = Math.pow(p2 - p1, 2);
        
        long sampleSizePerGroup = denominator > 0 ? (long) Math.ceil(numerator / denominator) : 0;
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
    
    /**
     * 标准正态分布累积分布函数（CDF）近似
     */
    private double normalCDF(double z) {
        // 使用Abramowitz and Stegun近似
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;
        
        int sign = z < 0 ? -1 : 1;
        z = Math.abs(z) / Math.sqrt(2);
        
        double t = 1.0 / (1.0 + p * z);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-z * z);
        
        return 0.5 * (1.0 + sign * y);
    }
    
    /**
     * 获取Z临界值（标准正态分布）
     */
    private double getZCritical(double confidence) {
        // 常用置信水平对应的Z值
        if (confidence >= 0.995) return 2.576;
        if (confidence >= 0.99) return 2.326;
        if (confidence >= 0.975) return 1.96;
        if (confidence >= 0.95) return 1.645;
        if (confidence >= 0.90) return 1.282;
        if (confidence >= 0.80) return 0.842;
        return 1.96; // 默认95%
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
        
        // 目前使用模拟数据，实际实现需要按时间分组查询事件数据
        // TODO: 实现真实的时间线数据查询
        java.util.List<Map<String, Object>> dataPoints = new java.util.ArrayList<>();
        
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime start = metadata.getExperiment().getStartTime();
        if (start == null) {
            start = now.minusDays(7);
        }
        
        // 生成模拟的时间线数据点
        int points = 7; // 默认7天
        if ("HOUR".equals(granularity)) {
            points = 24;
        } else if ("WEEK".equals(granularity)) {
            points = 4;
        }
        
        for (int i = 0; i < points; i++) {
            Map<String, Object> point = new HashMap<>();
            java.time.LocalDateTime pointTime;
            
            if ("HOUR".equals(granularity)) {
                pointTime = now.minusHours(points - 1 - i);
            } else if ("WEEK".equals(granularity)) {
                pointTime = start.plusWeeks(i);
            } else {
                pointTime = start.plusDays(i);
            }
            
            point.put("timestamp", pointTime);
            point.put("label", pointTime.toString());
            
            // 为每个组生成数据
            Map<String, Double> groupValues = new HashMap<>();
            if (metadata.getGroups() != null) {
                for (String groupId : metadata.getGroups().keySet()) {
                    // 模拟数据：实际应该从事件数据中计算
                    double baseValue = 0.10 + Math.random() * 0.05;
                    groupValues.put(groupId, baseValue);
                }
            }
            point.put("values", groupValues);
            
            dataPoints.add(point);
        }
        
        timeline.put("dataPoints", dataPoints);
        timeline.put("note", "时间线数据为模拟数据，实际实现需要按时间分组查询事件数据");
        
        return timeline;
    }
}

