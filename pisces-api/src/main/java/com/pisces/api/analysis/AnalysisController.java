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
}

