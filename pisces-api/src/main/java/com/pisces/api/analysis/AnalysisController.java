package com.pisces.api.analysis;

import com.pisces.common.model.Statistics;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据分析控制器（无用户系统版本）
 * 查询接口无需Token认证
 */
@RestController
@RequestMapping("/analysis")
public class AnalysisController {
    
    @Autowired
    private AnalysisService analysisService;
    
    /**
     * 获取实验统计数据
     */
    @GetMapping("/experiment/{id}/statistics")
    @NoTokenRequired
    public BaseResponse<Statistics> getStatistics(@PathVariable String id) {
        Statistics statistics = analysisService.getStatistics(id);
        return BaseResponse.of(statistics);
    }
    
    /**
     * 对比实验组
     */
    @GetMapping("/experiment/{id}/compare")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> compareGroups(@PathVariable String id) {
        Map<String, Object> comparison = analysisService.compareGroups(id);
        return BaseResponse.of(comparison);
    }
    
    /**
     * 传统统计显著性检验
     * 包含Z检验、p值、置信区间、提升率等指标
     * @param id 实验ID
     * @param variantGroupId 变体组ID
     * @param baselineGroupId 基准组ID
     * @param confidenceLevel 置信水平（默认0.95，即95%置信度）
     */
    @GetMapping("/experiment/{id}/significance")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> statisticalSignificanceTest(
            @PathVariable String id,
            @RequestParam String variantGroupId,
            @RequestParam String baselineGroupId,
            @RequestParam(required = false, defaultValue = "0.95") Double confidenceLevel) {
        Map<String, Object> result = analysisService.statisticalSignificanceTest(
                id, variantGroupId, baselineGroupId, confidenceLevel);
        return BaseResponse.of(result);
    }
    
    /**
     * 计算所需样本量（功效分析）
     * 用于实验设计阶段，计算达到统计显著性所需的样本量
     * @param baselineRate 基准转化率（如0.10表示10%）
     * @param minimumDetectableEffect 最小可检测效应（如0.10表示10%的相对提升）
     * @param power 统计功效（默认0.8，即80%）
     * @param significance 显著性水平（默认0.05，即5%）
     */
    @GetMapping("/sample-size")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> calculateSampleSize(
            @RequestParam Double baselineRate,
            @RequestParam(required = false, defaultValue = "0.10") Double minimumDetectableEffect,
            @RequestParam(required = false, defaultValue = "0.80") Double power,
            @RequestParam(required = false, defaultValue = "0.05") Double significance) {
        Map<String, Object> result = analysisService.calculateSampleSize(
                baselineRate, minimumDetectableEffect, power, significance);
        return BaseResponse.of(result);
    }
    
    /**
     * 获取贝叶斯分析结果（实时胜率计算）
     * AI赋能：基于贝叶斯统计方法，实时计算变体击败基准的概率，支持实验提前终止
     */
    @GetMapping("/experiment/{id}/bayesian")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> getBayesianAnalysis(@PathVariable String id) {
        Map<String, Object> analysis = analysisService.getBayesianAnalysis(id);
        return BaseResponse.of(analysis);
    }
    
    /**
     * 判断是否可以提前终止实验
     * AI赋能：基于贝叶斯胜率判断，当胜率≥95%时可提前终止实验并全量上线
     */
    @GetMapping("/experiment/{id}/early-stop")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> shouldEarlyStop(
            @PathVariable String id,
            @RequestParam String variantGroupId,
            @RequestParam String baselineGroupId,
            @RequestParam(required = false, defaultValue = "0.95") Double winRateThreshold) {
        Map<String, Object> result = analysisService.shouldEarlyStop(
                id, variantGroupId, baselineGroupId, winRateThreshold);
        return BaseResponse.of(result);
    }
    
    /**
     * 执行因果推断分析
     * AI赋能：通过DID、PSM、因果森林等方法，剥离混淆变量影响，精准计算处理效应
     * @param id 实验ID
     * @param method 分析方法：DID（双重差分法）、PSM（倾向得分匹配）、CAUSAL_FOREST（因果森林）
     * @param treatmentGroupId 处理组ID
     * @param controlGroupId 对照组ID
     * @param params 分析参数（根据方法不同，参数不同）
     *                - DID: beforePeriodStart, beforePeriodEnd, afterPeriodStart, afterPeriodEnd
     *                - PSM/CAUSAL_FOREST: userFeatures (用户特征列表)
     */
    @PostMapping("/experiment/{id}/causal-inference")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> causalInference(
            @PathVariable String id,
            @RequestParam String method,
            @RequestParam String treatmentGroupId,
            @RequestParam String controlGroupId,
            @RequestBody Map<String, Object> params) {
        Map<String, Object> result = analysisService.causalInference(
                id, treatmentGroupId, controlGroupId, method, params);
        return BaseResponse.of(result);
    }
    
    /**
     * 分析异质处理效应（HTE）
     * AI赋能：识别对策略敏感的用户群体，从"群体平均"到"个体精准"
     */
    @PostMapping("/experiment/{id}/hte")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> analyzeHTE(
            @PathVariable String id,
            @RequestParam String treatmentGroupId,
            @RequestParam String controlGroupId,
            @RequestBody java.util.List<String> userFeatures) {
        Map<String, Object> result = analysisService.analyzeHTE(
                id, treatmentGroupId, controlGroupId, userFeatures);
        return BaseResponse.of(result);
    }
    
    /**
     * 识别敏感用户群体
     * AI赋能：根据处理效应大小，将用户划分为高敏感、中敏感、低敏感群体
     */
    @PostMapping("/experiment/{id}/sensitive-groups")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> identifySensitiveGroups(
            @PathVariable String id,
            @RequestParam String treatmentGroupId,
            @RequestParam String controlGroupId,
            @RequestBody java.util.List<String> userFeatures) {
        Map<String, Object> result = analysisService.identifySensitiveGroups(
                id, treatmentGroupId, controlGroupId, userFeatures);
        return BaseResponse.of(result);
    }
    
    /**
     * 导出实验报告
     * 生成完整的实验分析报告，包含统计数据、分析结果、结论建议等
     */
    @GetMapping("/experiment/{id}/report")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> exportExperimentReport(@PathVariable String id) {
        Map<String, Object> report = analysisService.exportExperimentReport(id);
        return BaseResponse.of(report);
    }
    
    /**
     * 获取实验时间线数据
     * 用于展示实验指标随时间变化的趋势
     * @param id 实验ID
     * @param metricType 指标类型：CONVERSION_RATE（转化率）、CLICK_RATE（点击率）、VISITOR_COUNT（访客数）
     * @param granularity 时间粒度：HOUR（小时）、DAY（天）、WEEK（周）
     */
    @GetMapping("/experiment/{id}/timeline")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> getExperimentTimeline(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "CONVERSION_RATE") String metricType,
            @RequestParam(required = false, defaultValue = "DAY") String granularity) {
        Map<String, Object> timeline = analysisService.getExperimentTimeline(id, metricType, granularity);
        return BaseResponse.of(timeline);
    }
}

