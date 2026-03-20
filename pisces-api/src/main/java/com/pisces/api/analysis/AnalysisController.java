package com.pisces.api.analysis;

import com.pisces.common.model.ExperimentReportSnapshot;
import com.pisces.common.model.Statistics;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.AIDecisionService;
import com.pisces.service.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据分析控制器（无用户系统版本）
 * 查询接口无需Token认证
 */
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AIDecisionService aiDecisionService;
    
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
     * 生成并保存实验报告快照
     */
    @PostMapping("/experiment/{id}/report/snapshots")
    @NoTokenRequired
    public BaseResponse<ExperimentReportSnapshot> createReportSnapshot(
            @PathVariable String id,
            @RequestParam(value = "generatedBy", required = false, defaultValue = "system") String generatedBy) {
        ExperimentReportSnapshot snapshot = analysisService.createReportSnapshot(id, generatedBy);
        return BaseResponse.of("实验报告快照生成成功", snapshot);
    }

    /**
     * 查询实验报告快照列表
     */
    @GetMapping("/experiment/{id}/report/snapshots")
    @NoTokenRequired
    public BaseResponse<List<ExperimentReportSnapshot>> listReportSnapshots(@PathVariable String id) {
        List<ExperimentReportSnapshot> snapshots = analysisService.listReportSnapshots(id);
        return BaseResponse.of(snapshots);
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
    
    /**
     * AI智能实验解读
     * AI赋能：调用大模型分析实验数据，生成专业的分析报告和建议
     * @param id 实验ID
     * @return AI生成的分析结论、关键洞察和建议
     */
    @GetMapping("/experiment/{id}/ai-insights")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> getAIInsights(@PathVariable String id) {
        Map<String, Object> insights = analysisService.getAIInsights(id);
        return BaseResponse.of(insights);
    }

    /**
     * AI实验诊断
     * AI赋能：基于实验统计与数据质量事实返回结构化诊断结果
     *
     * @param id 实验ID
     * @return AI结构化诊断结果
     */
    @GetMapping("/experiment/{id}/ai-diagnosis")
    @NoTokenRequired
    public BaseResponse<AIDiagnosisResponse> getAIDiagnosis(@PathVariable String id) {
        return BaseResponse.of(aiDecisionService.diagnoseExperiment(id));
    }

    /**
     * AI毕业决策
     * AI赋能：基于实验统计与数据质量事实返回结构化毕业决策结果
     *
     * @param id 实验ID
     * @return AI结构化毕业决策结果
     */
    @GetMapping("/experiment/{id}/ai-graduation-decision")
    @NoTokenRequired
    public BaseResponse<AIGraduationDecisionResponse> getAIGraduationDecision(@PathVariable String id) {
        return BaseResponse.of(aiDecisionService.decideGraduation(id));
    }
    
    /**
     * AI实验设计建议
     * AI赋能：根据业务场景和目标，生成最佳实验设计方案
     * @param request 包含业务场景、目标指标、约束条件的请求体
     * @return AI推荐的实验设计方案
     */
    @PostMapping("/experiment/ai-design")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> getAIExperimentDesign(
            @RequestBody Map<String, Object> request) {
        String businessScenario = (String) request.get("businessScenario");
        String targetMetric = (String) request.get("targetMetric");
        @SuppressWarnings("unchecked")
        java.util.List<String> constraints = (java.util.List<String>) request.get("constraints");
        
        Map<String, Object> design = analysisService.getAIExperimentDesign(
                businessScenario, targetMetric, constraints);
        return BaseResponse.of(design);
    }

    /**
     * AI实验设计建议 V2
     * AI赋能：返回结构化实验设计结论，供后续实验草案映射使用
     *
     * @param request AI实验设计结构化请求
     * @return AI结构化实验设计结果
     */
    @PostMapping("/experiment/ai-design/v2")
    @NoTokenRequired
    public BaseResponse<AIDesignResponse> getAIExperimentDesignV2(@RequestBody AIDesignRequest request) {
        return BaseResponse.of(aiDecisionService.designExperiment(request));
    }
    
    /**
     * AI自动毕业决策
     * AI赋能：根据实验数据判断是否可以全量发布最佳变体
     * @param id 实验ID
     * @return 毕业决策结果，包含推荐变体、置信度、风险评估、毕业计划等
     */
    @GetMapping("/experiment/{id}/auto-graduate")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> autoGraduateDecision(@PathVariable String id) {
        Map<String, Object> decision = analysisService.autoGraduateDecision(id);
        return BaseResponse.of(decision);
    }
    
    /**
     * AI预测实验完成时间
     * AI赋能：根据当前数据趋势预测实验何时能达到统计显著性
     * @param id 实验ID
     * @return 预测结果，包含当前进度、预计完成时间、加速建议等
     */
    @GetMapping("/experiment/{id}/predict-completion")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> predictExperimentCompletion(@PathVariable String id) {
        Map<String, Object> prediction = analysisService.predictExperimentCompletion(id);
        return BaseResponse.of(prediction);
    }

    /**
     * SRM 检测（Sample Ratio Mismatch）
     * 检测实验各组的实际流量比例是否与预设比例一致。若存在 SRM，实验结论不可信。
     * @param id 实验ID
     * @return 卡方统计量、p 值、是否存在 SRM、各组实际/期望访客数等
     */
    @GetMapping("/experiment/{id}/srm")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> detectSRM(@PathVariable String id) {
        Map<String, Object> result = analysisService.detectSRM(id);
        return BaseResponse.of(result);
    }

    /**
     * 序贯检验（SPRT — Sequential Probability Ratio Test）
     * 适合实验期间持续监控，解决传统频繁查看导致的 Peeking Problem。
     * @param id              实验ID
     * @param variantGroupId  变体组ID
     * @param baselineGroupId 基准组ID
     * @param mde             最小可检测效应（相对，如 0.05 表示 5%）
     * @param alpha           I 类错误率（默认 0.05）
     * @param beta            II 类错误率（默认 0.20，即 power=0.80）
     * @return 当前决策（ACCEPT_H1 / ACCEPT_H0 / CONTINUE）、对数似然比及阈值
     */
    @GetMapping("/experiment/{id}/sequential")
    @NoTokenRequired
    public BaseResponse<Map<String, Object>> sequentialTest(
            @PathVariable String id,
            @RequestParam String variantGroupId,
            @RequestParam String baselineGroupId,
            @RequestParam(required = false, defaultValue = "0.05") Double mde,
            @RequestParam(required = false, defaultValue = "0.05") Double alpha,
            @RequestParam(required = false, defaultValue = "0.20") Double beta) {
        Map<String, Object> result = analysisService.sequentialTest(
                id, variantGroupId, baselineGroupId, mde, alpha, beta);
        return BaseResponse.of(result);
    }
}
