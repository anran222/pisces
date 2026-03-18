package com.pisces.service.service.impl;

import com.pisces.common.model.Event;
import com.pisces.service.service.CausalInferenceService;
import com.pisces.service.service.DataService;
import com.pisces.service.util.StatisticalUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 因果推断分析服务实现
 * 实现DID、PSM、因果森林等方法，用于剥离混淆变量影响，精准计算处理效应
 */
@Slf4j
@Service
public class CausalInferenceServiceImpl implements CausalInferenceService {

    private static final String METHOD_DID = "DID";

    private static final String METHOD_PSM = "PSM";

    private static final String METHOD_CAUSAL_FOREST = "CAUSAL_FOREST";

    private static final String STATUS_BLOCKED = "BLOCKED";

    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private static final String STATUS_READY = "READY";

    private static final List<String> SUPPORTED_COVARIATES = Arrays.asList(
            "viewCount", "clickCount", "eventCount", "rank");
    
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

        Map<String, Object> contract = validateDidRequest(beforePeriodStart, beforePeriodEnd, afterPeriodStart, afterPeriodEnd);
        if (isBlockedResult(contract)) {
            return contract;
        }

        LocalDateTime beforeStart = (LocalDateTime) contract.get("beforePeriodStart");
        LocalDateTime beforeEnd = (LocalDateTime) contract.get("beforePeriodEnd");
        LocalDateTime afterStart = (LocalDateTime) contract.get("afterPeriodStart");
        LocalDateTime afterEnd = (LocalDateTime) contract.get("afterPeriodEnd");

        long treatmentBeforeViews = dataService.getEventCountInTimeRange(experimentId, treatmentGroupId,
                Event.EventType.VIEW.name(), beforeStart, beforeEnd);
        long treatmentAfterViews = dataService.getEventCountInTimeRange(experimentId, treatmentGroupId,
                Event.EventType.VIEW.name(), afterStart, afterEnd);
        long controlBeforeViews = dataService.getEventCountInTimeRange(experimentId, controlGroupId,
                Event.EventType.VIEW.name(), beforeStart, beforeEnd);
        long controlAfterViews = dataService.getEventCountInTimeRange(experimentId, controlGroupId,
                Event.EventType.VIEW.name(), afterStart, afterEnd);

        List<String> blockingIssues = new ArrayList<>();
        if (treatmentBeforeViews <= 0) {
            blockingIssues.add("处理组 pre 窗口内没有 VIEW 事件");
        }
        if (treatmentAfterViews <= 0) {
            blockingIssues.add("处理组 post 窗口内没有 VIEW 事件");
        }
        if (controlBeforeViews <= 0) {
            blockingIssues.add("对照组 pre 窗口内没有 VIEW 事件");
        }
        if (controlAfterViews <= 0) {
            blockingIssues.add("对照组 post 窗口内没有 VIEW 事件");
        }
        if (!blockingIssues.isEmpty()) {
            return buildBlockedResult(METHOD_DID, STATUS_BLOCKED,
                    "DID 分析所需窗口数据不足",
                    blockingIssues,
                    Collections.emptyList(),
                    contract);
        }

        double treatmentBefore = calculateConversionRate(experimentId, treatmentGroupId, beforeStart, beforeEnd);
        double treatmentAfter = calculateConversionRate(experimentId, treatmentGroupId, afterStart, afterEnd);
        double controlBefore = calculateConversionRate(experimentId, controlGroupId, beforeStart, beforeEnd);
        double controlAfter = calculateConversionRate(experimentId, controlGroupId, afterStart, afterEnd);

        double treatmentDiff = treatmentAfter - treatmentBefore;
        double controlDiff = controlAfter - controlBefore;
        double didEstimate = treatmentDiff - controlDiff;

        double se = calculateStandardError(experimentId, treatmentGroupId, controlGroupId,
                beforeStart, beforeEnd, afterStart, afterEnd);
        double tStat = se > 0 ? didEstimate / se : 0.0;
        double pValue = calculatePValue(tStat);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", METHOD_DID);
        result.put("status", STATUS_READY);
        result.put("blocked", false);
        result.put("analysisReady", true);
        result.put("inputContract", contract);
        result.put("windowValidation", buildWindowValidation(treatmentBeforeViews, treatmentAfterViews,
                controlBeforeViews, controlAfterViews, beforeStart, beforeEnd, afterStart, afterEnd));
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

        Map<String, Object> contract = validatePsmRequest(userFeatures);
        if (isBlockedResult(contract)) {
            return contract;
        }
        @SuppressWarnings("unchecked")
        List<String> covariates = (List<String>) contract.get("usedCovariates");

        List<Event> treatEvents = dataService.getEvents(experimentId, treatmentGroupId);
        List<Event> ctrlEvents  = dataService.getEvents(experimentId, controlGroupId);

        if (treatEvents.isEmpty() || ctrlEvents.isEmpty()) {
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM分析所需事件数据不足，无法执行真实因果推断",
                    Arrays.asList("处理组或对照组事件数据为空"),
                    Collections.emptyList(),
                    contract);
        }

        Map<String, VisitorData> treatVisitors = buildVisitorMap(treatEvents, true);
        Map<String, VisitorData> ctrlVisitors  = buildVisitorMap(ctrlEvents, false);

        if (treatVisitors.isEmpty() || ctrlVisitors.isEmpty()) {
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM分析所需访客特征不足，无法执行真实因果推断",
                    Arrays.asList("处理组或对照组访客特征不足"),
                    Collections.emptyList(),
                    contract);
        }

        List<VisitorData> allVisitors = new ArrayList<>();
        allVisitors.addAll(treatVisitors.values());
        allVisitors.addAll(ctrlVisitors.values());

        Map<String, Double> featureStdMap = normalizeFeatures(allVisitors, covariates);
        boolean allConstantFeatures = featureStdMap.values().stream().allMatch(std -> std == null || std <= 0.0);
        if (allConstantFeatures) {
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM 协变量没有可用方差，无法估计倾向得分",
                    Arrays.asList("请求的协变量在当前样本中都是常量"),
                    Collections.emptyList(),
                    contract);
        }

        double[] weights = fitLogisticRegression(allVisitors, covariates.size());
        for (VisitorData v : allVisitors) {
            double linear = weights[0];
            for (int i = 0; i < covariates.size(); i++) {
                linear += weights[i + 1] * v.normalizedFeatures[i];
            }
            v.propensityScore = sigmoid(linear);
        }

        double caliper = computeCaliper(allVisitors);
        List<double[]> matchedPairs = nearestNeighborMatch(treatVisitors.values(), ctrlVisitors.values(), caliper);

        if (matchedPairs.size() < 2) {
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM 匹配对不足，无法给出稳定的处理效应估计",
                    Arrays.asList("满足 caliper 的匹配对少于 2 对"),
                    Collections.emptyList(),
                    contract);
        }

        double sumDiff = 0;
        for (double[] pair : matchedPairs) {
            sumDiff += pair[0] - pair[1];
        }
        double ate = matchedPairs.isEmpty() ? 0 : sumDiff / matchedPairs.size();

        double variance = 0;
        for (double[] pair : matchedPairs) {
            variance += Math.pow((pair[0] - pair[1]) - ate, 2);
        }
        double se = matchedPairs.size() > 1 ? Math.sqrt(variance / (matchedPairs.size() * (matchedPairs.size() - 1))) : 0;
        double tStat = se > 0 ? ate / se : 0;
        double pValue = StatisticalUtils.zToPValue(tStat);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", METHOD_PSM);
        result.put("status", STATUS_READY);
        result.put("blocked", false);
        result.put("analysisReady", true);
        result.put("inputContract", contract);
        result.put("covariateReadiness", buildCovariateReadiness(covariates, featureStdMap));
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
        int clickCount;
        int eventCount;
        int rank;        // 事件序号（时间先后代理）
        double[] normalizedFeatures;
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
            if (Event.EventType.CLICK.equals(e.getEventType())) vd.clickCount++;
            if (Event.EventType.CONVERT.equals(e.getEventType())) vd.outcome = 1;
            vd.eventCount++;
            if (vd.rank == 0) vd.rank = ++rank;
        }
        return map;
    }

    private Map<String, Double> normalizeFeatures(List<VisitorData> list, List<String> covariates) {
        Map<String, Double> stdMap = new LinkedHashMap<>();
        Map<String, Double> meanMap = new LinkedHashMap<>();
        for (String covariate : covariates) {
            double mean = list.stream()
                    .mapToDouble(v -> extractCovariateValue(v, covariate))
                    .average()
                    .orElse(0.0);
            double variance = list.stream()
                    .mapToDouble(v -> Math.pow(extractCovariateValue(v, covariate) - mean, 2))
                    .average()
                    .orElse(0.0);
            double std = Math.sqrt(variance);
            meanMap.put(covariate, mean);
            stdMap.put(covariate, std);
        }
        for (VisitorData v : list) {
            v.normalizedFeatures = new double[covariates.size()];
            for (int i = 0; i < covariates.size(); i++) {
                String covariate = covariates.get(i);
                double std = stdMap.getOrDefault(covariate, 0.0);
                double mean = meanMap.getOrDefault(covariate, 0.0);
                double raw = extractCovariateValue(v, covariate);
                v.normalizedFeatures[i] = std > 0 ? (raw - mean) / std : 0.0;
            }
        }
        return stdMap;
    }

    private double extractCovariateValue(VisitorData visitor, String covariate) {
        if ("viewCount".equals(covariate)) {
            return visitor.viewCount;
        }
        if ("clickCount".equals(covariate)) {
            return visitor.clickCount;
        }
        if ("eventCount".equals(covariate)) {
            return visitor.eventCount;
        }
        if ("rank".equals(covariate)) {
            return visitor.rank;
        }
        return 0.0;
    }

    /** 逻辑回归（梯度下降） */
    private double[] fitLogisticRegression(List<VisitorData> data, int featureSize) {
        double[] weights = new double[featureSize + 1];
        double lr = 0.1;
        for (int iter = 0; iter < 100; iter++) {
            double[] gradients = new double[featureSize + 1];
            for (VisitorData v : data) {
                double pred = sigmoid(dot(weights, v.normalizedFeatures));
                double err = pred - v.treatment;
                gradients[0] += err;
                for (int i = 0; i < featureSize; i++) {
                    gradients[i + 1] += err * v.normalizedFeatures[i];
                }
            }
            int n = data.size();
            for (int i = 0; i < weights.length; i++) {
                weights[i] -= lr * gradients[i] / n;
            }
        }
        return weights;
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
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requiredInputs", Collections.singletonList("userFeatures"));
        contract.put("supportedCovariates", SUPPORTED_COVARIATES);
        contract.put("requestedCovariates", sanitizeCovariates(userFeatures));
        return buildBlockedResult(METHOD_CAUSAL_FOREST, STATUS_UNAVAILABLE,
                "因果森林分析尚未接入真实模型，当前仅保留接口契约",
                Collections.singletonList("未接入真实因果森林模型"),
                Collections.emptyList(),
                contract);
    }
    
    /**
     * 计算转化率
     */
    private double calculateConversionRate(String experimentId, String groupId,
                                          LocalDateTime start, LocalDateTime end) {
        long views = dataService.getEventCountInTimeRange(experimentId, groupId, Event.EventType.VIEW.name(), start, end);
        long converts = dataService.getEventCountInTimeRange(experimentId, groupId, Event.EventType.CONVERT.name(), start, end);
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

        long safeN = 1;
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
        } catch (DateTimeParseException e) {
            log.warn("解析日期时间失败: {}", dateTimeStr);
            return null;
        }
    }

    private Map<String, Object> validateDidRequest(String beforePeriodStart, String beforePeriodEnd,
                                                   String afterPeriodStart, String afterPeriodEnd) {
        List<String> blockingIssues = new ArrayList<>();
        LocalDateTime beforeStart = parseDateTime(beforePeriodStart);
        LocalDateTime beforeEnd = parseDateTime(beforePeriodEnd);
        LocalDateTime afterStart = parseDateTime(afterPeriodStart);
        LocalDateTime afterEnd = parseDateTime(afterPeriodEnd);

        if (beforeStart == null) {
            blockingIssues.add("beforePeriodStart 格式错误");
        }
        if (beforeEnd == null) {
            blockingIssues.add("beforePeriodEnd 格式错误");
        }
        if (afterStart == null) {
            blockingIssues.add("afterPeriodStart 格式错误");
        }
        if (afterEnd == null) {
            blockingIssues.add("afterPeriodEnd 格式错误");
        }
        if (beforeStart != null && beforeEnd != null && !beforeStart.isBefore(beforeEnd)) {
            blockingIssues.add("beforePeriodStart 必须早于 beforePeriodEnd");
        }
        if (afterStart != null && afterEnd != null && !afterStart.isBefore(afterEnd)) {
            blockingIssues.add("afterPeriodStart 必须早于 afterPeriodEnd");
        }
        if (beforeEnd != null && afterStart != null && !beforeEnd.isBefore(afterStart)) {
            blockingIssues.add("beforePeriodEnd 必须早于 afterPeriodStart，且前后窗口不能重叠");
        }
        if (!blockingIssues.isEmpty()) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("requiredInputs", Arrays.asList("beforePeriodStart", "beforePeriodEnd",
                    "afterPeriodStart", "afterPeriodEnd"));
            Map<String, Object> providedInputs = new LinkedHashMap<>();
            providedInputs.put("beforePeriodStart", beforePeriodStart);
            providedInputs.put("beforePeriodEnd", beforePeriodEnd);
            providedInputs.put("afterPeriodStart", afterPeriodStart);
            providedInputs.put("afterPeriodEnd", afterPeriodEnd);
            contract.put("providedInputs", providedInputs);
            return buildBlockedResult(METHOD_DID, STATUS_BLOCKED,
                    "DID 输入窗口不合法",
                    blockingIssues,
                    Collections.emptyList(),
                    contract);
        }

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requiredInputs", Arrays.asList("beforePeriodStart", "beforePeriodEnd",
                "afterPeriodStart", "afterPeriodEnd"));
        contract.put("beforePeriodStart", beforeStart);
        contract.put("beforePeriodEnd", beforeEnd);
        contract.put("afterPeriodStart", afterStart);
        contract.put("afterPeriodEnd", afterEnd);
        contract.put("windowReady", true);
        return contract;
    }

    private Map<String, Object> validatePsmRequest(List<String> userFeatures) {
        List<String> requestedCovariates = sanitizeCovariates(userFeatures);
        if (requestedCovariates.isEmpty()) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("requiredInputs", Collections.singletonList("userFeatures"));
            contract.put("supportedCovariates", SUPPORTED_COVARIATES);
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM 需要显式协变量输入",
                    Collections.singletonList("userFeatures 不能为空"),
                    Collections.emptyList(),
                    contract);
        }

        List<String> unsupportedCovariates = requestedCovariates.stream()
                .filter(covariate -> !SUPPORTED_COVARIATES.contains(covariate))
                .collect(Collectors.toList());
        if (!unsupportedCovariates.isEmpty()) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("requiredInputs", Collections.singletonList("userFeatures"));
            contract.put("supportedCovariates", SUPPORTED_COVARIATES);
            contract.put("requestedCovariates", requestedCovariates);
            contract.put("unsupportedCovariates", unsupportedCovariates);
            return buildBlockedResult(METHOD_PSM, STATUS_BLOCKED,
                    "PSM 请求了当前不支持的协变量",
                    unsupportedCovariates.stream()
                            .map(value -> "不支持的协变量: " + value)
                            .collect(Collectors.toList()),
                    Collections.emptyList(),
                    contract);
        }

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requiredInputs", Collections.singletonList("userFeatures"));
        contract.put("supportedCovariates", SUPPORTED_COVARIATES);
        contract.put("requestedCovariates", requestedCovariates);
        contract.put("usedCovariates", requestedCovariates);
        contract.put("covariateReady", true);
        return contract;
    }

    private Map<String, Object> buildBlockedResult(String method, String status, String reason,
                                                   List<String> blockingIssues, List<String> warnings,
                                                   Map<String, Object> contract) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method);
        result.put("status", status);
        result.put("blocked", true);
        result.put("analysisReady", false);
        result.put("reason", reason);
        result.put("blockingIssues", blockingIssues != null ? blockingIssues : Collections.emptyList());
        result.put("warnings", warnings != null ? warnings : Collections.emptyList());
        if (contract != null && !contract.isEmpty()) {
            result.put("inputContract", contract);
        }
        return result;
    }

    private Map<String, Object> buildWindowValidation(long treatmentBeforeViews, long treatmentAfterViews,
                                                      long controlBeforeViews, long controlAfterViews,
                                                      LocalDateTime beforeStart, LocalDateTime beforeEnd,
                                                      LocalDateTime afterStart, LocalDateTime afterEnd) {
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("method", METHOD_DID);
        validation.put("windowReady", true);

        Map<String, Object> beforeWindow = new LinkedHashMap<>();
        beforeWindow.put("start", beforeStart);
        beforeWindow.put("end", beforeEnd);
        beforeWindow.put("treatmentViewCount", treatmentBeforeViews);
        beforeWindow.put("controlViewCount", controlBeforeViews);

        Map<String, Object> afterWindow = new LinkedHashMap<>();
        afterWindow.put("start", afterStart);
        afterWindow.put("end", afterEnd);
        afterWindow.put("treatmentViewCount", treatmentAfterViews);
        afterWindow.put("controlViewCount", controlAfterViews);

        validation.put("beforeWindow", beforeWindow);
        validation.put("afterWindow", afterWindow);
        return validation;
    }

    private Map<String, Object> buildCovariateReadiness(List<String> covariates, Map<String, Double> stdMap) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("requestedCovariates", covariates);
        readiness.put("supportedCovariates", SUPPORTED_COVARIATES);
        readiness.put("featureStdDeviations", stdMap);
        readiness.put("covariateReady", true);
        return readiness;
    }

    private List<String> sanitizeCovariates(List<String> userFeatures) {
        if (userFeatures == null || userFeatures.isEmpty()) {
            return Collections.emptyList();
        }
        return userFeatures.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isBlockedResult(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("blocked"));
    }

    private double dot(double[] weights, double[] features) {
        double linear = weights[0];
        for (int i = 0; i < features.length; i++) {
            linear += weights[i + 1] * features[i];
        }
        return linear;
    }
}
