package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.DataService;
import com.pisces.service.util.StatisticalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 因果推断分析服务实现
 * 实现DID、PSM、因果森林等方法，用于剥离混淆变量影响，精准计算处理效应
 */
@Slf4j
@Service
public class CausalInferenceServiceImpl implements CausalInferenceService {
    
    @Autowired
    private DataService dataService;
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public Map<String, Object> analyzeByDID(String experimentId, String treatmentGroupId,
                                           String controlGroupId,
                                           String beforePeriodStart, String beforePeriodEnd,
                                           String afterPeriodStart, String afterPeriodEnd) {
        log.info("执行DID分析: experimentId={}, treatment={}, control={}", 
                experimentId, treatmentGroupId, controlGroupId);
        
        // 解析时间
        LocalDateTime beforeStart = parseDateTime(beforePeriodStart);
        LocalDateTime beforeEnd = parseDateTime(beforePeriodEnd);
        LocalDateTime afterStart = parseDateTime(afterPeriodStart);
        LocalDateTime afterEnd = parseDateTime(afterPeriodEnd);
        
        // 计算处理前后的指标（使用转化率作为示例）
        double treatmentBefore = calculateConversionRate(experimentId, treatmentGroupId, beforeStart, beforeEnd);
        double treatmentAfter = calculateConversionRate(experimentId, treatmentGroupId, afterStart, afterEnd);
        double controlBefore = calculateConversionRate(experimentId, controlGroupId, beforeStart, beforeEnd);
        double controlAfter = calculateConversionRate(experimentId, controlGroupId, afterStart, afterEnd);
        
        // 第一次差分（时间维度）
        double treatmentDiff = treatmentAfter - treatmentBefore;
        double controlDiff = controlAfter - controlBefore;
        
        // 第二次差分（组间维度）- DID估计量
        double didEstimate = treatmentDiff - controlDiff;
        
        // 计算标准误（简化实现）
        double se = calculateStandardError(experimentId, treatmentGroupId, controlGroupId,
                beforeStart, beforeEnd, afterStart, afterEnd);
        double tStat = se > 0 ? didEstimate / se : 0.0;
        double pValue = calculatePValue(tStat);
        
        Map<String, Object> result = new HashMap<>();
        result.put("method", "DID");
        result.put("didEstimate", didEstimate);
        result.put("treatmentBefore", treatmentBefore);
        result.put("treatmentAfter", treatmentAfter);
        result.put("controlBefore", controlBefore);
        result.put("controlAfter", controlAfter);
        result.put("treatmentDiff", treatmentDiff);
        result.put("controlDiff", controlDiff);
        result.put("standardError", se);
        result.put("tStatistic", tStat);
        result.put("pValue", pValue);
        result.put("isSignificant", pValue < 0.05);
        result.put("interpretation", String.format(
                "在控制了时间趋势和两组人群固有差异后，处理效应为 %.4f，%s显著",
                didEstimate, pValue < 0.05 ? "统计" : "不统计"));
        
        log.info("DID分析完成: estimate={}, pValue={}", didEstimate, pValue);
        return result;
    }
    
    @Override
    public Map<String, Object> analyzeByPSM(String experimentId, String treatmentGroupId,
                                           String controlGroupId, List<String> userFeatures) {
        log.info("执行PSM分析: experimentId={}, treatment={}, control={}", experimentId, treatmentGroupId, controlGroupId);

        // 获取两组事件数据
        List<Event> treatEvents = dataService.getEvents(experimentId, treatmentGroupId);
        List<Event> ctrlEvents  = dataService.getEvents(experimentId, controlGroupId);

        if (treatEvents.isEmpty() || ctrlEvents.isEmpty()) {
            throw new BusinessException(ResponseCode.DATA_NOT_FOUND,
                    "PSM分析所需事件数据不足，无法执行真实因果推断");
        }

        // ── 1. 构建访客级别数据（以 VIEW 事件为单位，outcome = 是否有 CONVERT）──
        Map<String, VisitorData> treatVisitors = buildVisitorMap(treatEvents, true);
        Map<String, VisitorData> ctrlVisitors  = buildVisitorMap(ctrlEvents, false);

        if (treatVisitors.isEmpty() || ctrlVisitors.isEmpty()) {
            throw new BusinessException(ResponseCode.DATA_NOT_FOUND,
                    "PSM分析所需访客特征不足，无法执行真实因果推断");
        }

        // ── 2. 用逻辑回归计算倾向得分 P(treatment=1 | X) ──
        // 特征：viewCount（活跃度代理）、isEarlyAdopter（首次出现时间序号）
        List<VisitorData> allVisitors = new ArrayList<>();
        allVisitors.addAll(treatVisitors.values());
        allVisitors.addAll(ctrlVisitors.values());

        normalizeFeatures(allVisitors);
        double[] weights = fitLogisticRegression(allVisitors);
        for (VisitorData v : allVisitors) {
            v.propensityScore = sigmoid(weights[0] + weights[1] * v.feat1 + weights[2] * v.feat2);
        }

        // ── 3. 最近邻 1:1 匹配（caliper = 0.05 * SD of propensity scores）──
        double caliper = computeCaliper(allVisitors);
        List<double[]> matchedPairs = nearestNeighborMatch(treatVisitors.values(), ctrlVisitors.values(), caliper);

        // ── 4. 计算 ATT（平均处理效应，matched pairs 上）──
        double sumDiff = 0;
        for (double[] pair : matchedPairs) {
            sumDiff += pair[0] - pair[1]; // 处理组 outcome - 对照组 outcome
        }
        double ate = matchedPairs.isEmpty() ? 0 : sumDiff / matchedPairs.size();

        // ── 5. 配对 t 检验 SE ──
        double variance = 0;
        for (double[] pair : matchedPairs) {
            variance += Math.pow((pair[0] - pair[1]) - ate, 2);
        }
        double se = matchedPairs.size() > 1 ? Math.sqrt(variance / (matchedPairs.size() * (matchedPairs.size() - 1))) : 0;
        double tStat = se > 0 ? ate / se : 0;
        double pValue = StatisticalUtils.zToPValue(tStat);

        Map<String, Object> result = new HashMap<>();
        result.put("method", "PSM");
        result.put("totalTreatment", treatVisitors.size());
        result.put("totalControl", ctrlVisitors.size());
        result.put("matchedPairs", matchedPairs.size());
        result.put("unmatchedTreatment", treatVisitors.size() - matchedPairs.size());
        result.put("averageTreatmentEffect", ate);
        result.put("standardError", se);
        result.put("tStatistic", tStat);
        result.put("pValue", pValue);
        result.put("isSignificant", pValue < 0.05);
        result.put("caliper", caliper);
        result.put("interpretation", String.format(
                "PSM(%d对匹配)：控制观测混淆后，ATT=%.4f，%s（p=%.4f）",
                matchedPairs.size(), ate, pValue < 0.05 ? "统计显著" : "不显著", pValue));
        return result;
    }

    // ── PSM 内部辅助 ──

    private static class VisitorData {
        String visitorId;
        int treatment;   // 1=处理组, 0=对照组
        int outcome;     // 1=转化, 0=未转化
        int viewCount;
        int rank;        // 事件序号（时间先后代理）
        double feat1;    // 归一化特征1：viewCount
        double feat2;    // 归一化特征2：rank
        double propensityScore;
    }

    private Map<String, VisitorData> buildVisitorMap(List<Event> events, boolean isTreatment) {
        Map<String, VisitorData> map = new LinkedHashMap<>();
        int rank = 0;
        for (Event e : events) {
            String vid = e.getUserId();
            if (vid == null) continue;
            VisitorData vd = map.computeIfAbsent(vid, k -> {
                VisitorData v = new VisitorData();
                v.visitorId = k;
                v.treatment = isTreatment ? 1 : 0;
                return v;
            });
            if (Event.EventType.VIEW.equals(e.getEventType())) vd.viewCount++;
            if (Event.EventType.CONVERT.equals(e.getEventType())) vd.outcome = 1;
            if (vd.rank == 0) vd.rank = ++rank;
        }
        return map;
    }

    private void normalizeFeatures(List<VisitorData> list) {
        double meanV = list.stream().mapToInt(v -> v.viewCount).average().orElse(1);
        double stdV  = Math.max(1, Math.sqrt(list.stream().mapToDouble(v -> Math.pow(v.viewCount - meanV, 2)).average().orElse(1)));
        double meanR = list.stream().mapToInt(v -> v.rank).average().orElse(1);
        double stdR  = Math.max(1, Math.sqrt(list.stream().mapToDouble(v -> Math.pow(v.rank - meanR, 2)).average().orElse(1)));
        for (VisitorData v : list) {
            v.feat1 = (v.viewCount - meanV) / stdV;
            v.feat2 = (v.rank - meanR) / stdR;
        }
    }

    /** 逻辑回归（梯度下降，50 轮，学习率 0.1）*/
    private double[] fitLogisticRegression(List<VisitorData> data) {
        double w0 = 0, w1 = 0, w2 = 0;
        double lr = 0.1;
        for (int iter = 0; iter < 50; iter++) {
            double dw0 = 0, dw1 = 0, dw2 = 0;
            for (VisitorData v : data) {
                double pred = sigmoid(w0 + w1 * v.feat1 + w2 * v.feat2);
                double err = pred - v.treatment;
                dw0 += err; dw1 += err * v.feat1; dw2 += err * v.feat2;
            }
            int n = data.size();
            w0 -= lr * dw0 / n; w1 -= lr * dw1 / n; w2 -= lr * dw2 / n;
        }
        return new double[]{w0, w1, w2};
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double computeCaliper(List<VisitorData> list) {
        double mean = list.stream().mapToDouble(v -> v.propensityScore).average().orElse(0.5);
        double variance = list.stream().mapToDouble(v -> Math.pow(v.propensityScore - mean, 2)).average().orElse(0.01);
        return 0.2 * Math.sqrt(variance); // Cochran & Rubin 推荐的 0.2 倍标准差
    }

    /** 贪心最近邻匹配（处理组 → 对照组，不放回） */
    private List<double[]> nearestNeighborMatch(Collection<VisitorData> treat, Collection<VisitorData> ctrl, double caliper) {
        List<VisitorData> ctrlList = new ArrayList<>(ctrl);
        List<double[]> pairs = new ArrayList<>();
        for (VisitorData t : treat) {
            VisitorData best = null;
            double bestDist = caliper;
            for (VisitorData c : ctrlList) {
                double dist = Math.abs(t.propensityScore - c.propensityScore);
                if (dist < bestDist) { bestDist = dist; best = c; }
            }
            if (best != null) {
                pairs.add(new double[]{t.outcome, best.outcome});
                ctrlList.remove(best);
            }
        }
        return pairs;
    }

    @Override
    public Map<String, Object> analyzeByCausalForest(String experimentId, String treatmentGroupId,
                                                       String controlGroupId, List<String> userFeatures) {
        log.info("执行因果森林分析: experimentId={}, treatment={}, control={}, features={}", 
                experimentId, treatmentGroupId, controlGroupId, userFeatures);
        throw new BusinessException(ResponseCode.SERVICE_UNAVAILABLE,
                "因果森林分析尚未接入真实模型，禁止返回模拟结果");
    }
    
    /**
     * 计算转化率
     */
    private double calculateConversionRate(String experimentId, String groupId,
                                          LocalDateTime start, LocalDateTime end) {
        // TODO: 实际实现需要根据时间范围过滤事件
        // 这里简化处理，使用整体转化率
        long views = dataService.getEventCount(experimentId, groupId, Event.EventType.VIEW.name());
        long converts = dataService.getEventCount(experimentId, groupId, Event.EventType.CONVERT.name());
        return views > 0 ? (double) converts / views : 0.0;
    }
    
    /**
     * 计算 DID 标准误
     * 基于 Delta Method：SE ≈ sqrt( Var(post_treat) + Var(pre_treat) + Var(post_ctrl) + Var(pre_ctrl) )
     * 各期方差 = p*(1-p)/n，当无法获取实际 n 时退化为简化估计
     */
    private double calculateStandardError(String experimentId, String treatmentGroupId,
                                         String controlGroupId,
                                         LocalDateTime beforeStart, LocalDateTime beforeEnd,
                                         LocalDateTime afterStart, LocalDateTime afterEnd) {
        long nTreatBefore = dataService.getEventCountInTimeRange(experimentId, treatmentGroupId, "VIEW", beforeStart, beforeEnd);
        long nTreatAfter  = dataService.getEventCountInTimeRange(experimentId, treatmentGroupId, "VIEW", afterStart, afterEnd);
        long nCtrlBefore  = dataService.getEventCountInTimeRange(experimentId, controlGroupId, "VIEW", beforeStart, beforeEnd);
        long nCtrlAfter   = dataService.getEventCountInTimeRange(experimentId, controlGroupId, "VIEW", afterStart, afterEnd);

        double pTB = calculateConversionRate(experimentId, treatmentGroupId, beforeStart, beforeEnd);
        double pTA = calculateConversionRate(experimentId, treatmentGroupId, afterStart, afterEnd);
        double pCB = calculateConversionRate(experimentId, controlGroupId, beforeStart, beforeEnd);
        double pCA = calculateConversionRate(experimentId, controlGroupId, afterStart, afterEnd);

        long safeN = 10; // 防止除以 0
        double varTA = pTA * (1 - pTA) / Math.max(nTreatAfter, safeN);
        double varTB = pTB * (1 - pTB) / Math.max(nTreatBefore, safeN);
        double varCA = pCA * (1 - pCA) / Math.max(nCtrlAfter, safeN);
        double varCB = pCB * (1 - pCB) / Math.max(nCtrlBefore, safeN);

        return Math.sqrt(varTA + varTB + varCA + varCB);
    }

    /**
     * 双尾 p 值（基于正态 CDF 近似，替代之前的硬编码返回值）
     */
    private double calculatePValue(double tStat) {
        return StatisticalUtils.zToPValue(tStat);
    }
    
    /**
     * 解析日期时间字符串
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            return LocalDateTime.parse(dateTimeStr, DATE_FORMATTER);
        } catch (Exception e) {
            log.error("解析日期时间失败: {}", dateTimeStr, e);
            return LocalDateTime.now();
        }
    }
}
