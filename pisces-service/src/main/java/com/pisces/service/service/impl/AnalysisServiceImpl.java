package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.Statistics;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.TrafficService;
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
    private TrafficService trafficService;
    
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
        
        Map<String, Statistics.GroupStatistics> groupStatsMap = new HashMap<>();
        
        // 遍历所有实验组
        if (metadata.getGroups() != null) {
            for (String groupId : metadata.getGroups().keySet()) {
                Statistics.GroupStatistics groupStats = calculateGroupStatistics(
                        experimentId, groupId);
                groupStatsMap.put(groupId, groupStats);
            }
        }
        
        statistics.setGroupStatistics(groupStatsMap);
        return statistics;
    }
    
    /**
     * 计算实验组统计数据
     */
    private Statistics.GroupStatistics calculateGroupStatistics(String experimentId, String groupId) {
        Statistics.GroupStatistics groupStats = new Statistics.GroupStatistics();
        groupStats.setGroupId(groupId);
        
        // 计算用户数（从流量分配服务获取）
        // 这里简化处理，实际应该统计实际分配的用户数
        long userCount = 0; // TODO: 从TrafficService获取实际用户数
        
        // 计算事件统计
        Map<String, Long> eventCounts = new HashMap<>();
        String viewType = Event.EventType.VIEW.name();
        String clickType = Event.EventType.CLICK.name();
        String convertType = Event.EventType.CONVERT.name();
        
        eventCounts.put(viewType, dataService.getEventCount(experimentId, groupId, viewType));
        eventCounts.put(clickType, dataService.getEventCount(experimentId, groupId, clickType));
        eventCounts.put(convertType, dataService.getEventCount(experimentId, groupId, convertType));
        
        groupStats.setEventCounts(eventCounts);
        
        // 计算转化率
        long viewCount = eventCounts.getOrDefault(viewType, 0L);
        long convertCount = eventCounts.getOrDefault(convertType, 0L);
        double conversionRate = viewCount > 0 ? (double) convertCount / viewCount : 0.0;
        groupStats.setConversionRate(conversionRate);
        
        groupStats.setUserCount(userCount);
        
        return groupStats;
    }
    
    /**
     * 对比实验组
     */
    @Override
    public Map<String, Object> compareGroups(String experimentId) {
        Statistics statistics = getStatistics(experimentId);
        // getStatistics 已经会抛出异常，这里不需要再判断
        
        Map<String, Object> comparison = new HashMap<>();
        Map<String, Statistics.GroupStatistics> groupStats = statistics.getGroupStatistics();
        
        if (groupStats.size() < 2) {
            comparison.put("message", "至少需要2个实验组才能对比");
            return comparison;
        }
        
        // 获取第一个组作为基准
        String baselineGroup = groupStats.keySet().iterator().next();
        Statistics.GroupStatistics baseline = groupStats.get(baselineGroup);
        
        comparison.put("baseline", baselineGroup);
        comparison.put("baselineStats", baseline);
        
        // 对比其他组
        Map<String, Map<String, Object>> comparisons = new HashMap<>();
        for (Map.Entry<String, Statistics.GroupStatistics> entry : groupStats.entrySet()) {
            if (!entry.getKey().equals(baselineGroup)) {
                Map<String, Object> comp = compareWithBaseline(baseline, entry.getValue());
                comparisons.put(entry.getKey(), comp);
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
        double baselineRate = baseline.getConversionRate();
        double targetRate = target.getConversionRate();
        double rateDiff = targetRate - baselineRate;
        double rateChangePercent = baselineRate > 0 ? (rateDiff / baselineRate) * 100 : 0;
        
        comparison.put("conversionRate", targetRate);
        comparison.put("conversionRateChange", rateDiff);
        comparison.put("conversionRateChangePercent", rateChangePercent);
        
        // 事件数对比
        Map<String, Long> baselineEvents = baseline.getEventCounts();
        Map<String, Long> targetEvents = target.getEventCounts();
        
        Map<String, Map<String, Object>> eventComparison = new HashMap<>();
        Set<String> eventTypes = baselineEvents.keySet();
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
}

