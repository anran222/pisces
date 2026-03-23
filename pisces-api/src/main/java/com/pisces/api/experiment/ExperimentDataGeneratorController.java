package com.pisces.api.experiment;

import com.pisces.api.experiment.response.ExperimentDemoResponse;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.ExperimentDataGeneratorService;
import com.pisces.service.service.ExperimentDemoService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实验演示控制器
 */
@RestController
@RequestMapping("/experiments/generator")
@NoTokenRequired
@AllArgsConstructor
public class ExperimentDataGeneratorController {

    private static final int DEFAULT_VISITOR_COUNT_PER_GROUP = 150;
    private static final int DEFAULT_DAYS_SPAN = 7;

    private final ExperimentDemoService experimentDemoService;
    private final ExperimentDataGeneratorService experimentDataGeneratorService;

    /**
     * 生成二手手机售卖演示实验。
     *
     * @return 达标与未达标实验结果
     */
    @PostMapping("/demo")
    public BaseResponse<ExperimentDemoResponse> generateUsedPhoneDemo() {
        ExperimentDemoService.ExperimentDemoResult demoResult = experimentDemoService.generateUsedPhoneDemo();
        return BaseResponse.of("实验演示生成成功", toResponse(demoResult));
    }

    /**
     * 为已有实验补充真实事件数据。
     *
     * @param experimentId 实验ID
     * @param request      补数请求
     * @return 操作结果
     */
    @PostMapping("/{experimentId}/simulate")
    public BaseResponse<Void> generateDataForExistingExperiment(
            @PathVariable String experimentId,
            @RequestBody(required = false) ExistingExperimentDataGenerateRequest request) {
        int visitorCountPerGroup = request == null || request.getVisitorCount() == null
                ? DEFAULT_VISITOR_COUNT_PER_GROUP : request.getVisitorCount();
        int daysSpan = request == null || request.getDaysAgo() == null
                ? DEFAULT_DAYS_SPAN : request.getDaysAgo();
        experimentDataGeneratorService.generateDataForExistingExperiment(experimentId, visitorCountPerGroup, daysSpan);
        return BaseResponse.of("实验数据生成完成", null);
    }

    private ExperimentDemoResponse toResponse(ExperimentDemoService.ExperimentDemoResult demoResult) {
        ExperimentDemoResponse response = new ExperimentDemoResponse();
        response.setQualifiedExperiment(toCaseResponse(demoResult.getQualifiedExperiment()));
        response.setUnqualifiedExperiment(toCaseResponse(demoResult.getUnqualifiedExperiment()));
        return response;
    }

    private ExperimentDemoResponse.ExperimentCaseResponse toCaseResponse(
            ExperimentDemoService.ExperimentCaseResult caseResult) {
        ExperimentDemoResponse.ExperimentCaseResponse response = new ExperimentDemoResponse.ExperimentCaseResponse();
        response.setExperimentId(caseResult.getExperimentId());
        response.setExperimentName(caseResult.getExperimentName());
        response.setDemoTag(caseResult.getDemoTag());
        response.setBaselineGroupId(caseResult.getBaselineGroupId());
        response.setWinningGroupId(caseResult.getWinningGroupId());
        response.setCanGraduate(caseResult.getCanGraduate());
        response.setCanStop(caseResult.getCanStop());
        response.setAiDecision(caseResult.getAiDecision());
        response.setAiGuardrailStatus(caseResult.getAiGuardrailStatus());
        response.setAiSummary(caseResult.getAiSummary());
        response.setPrimaryMetricKey(caseResult.getPrimaryMetricKey());
        response.setGroupCount(caseResult.getGroupCount());
        response.setSchemaFieldCount(caseResult.getSchemaFieldCount());
        response.setBaselineConversionRate(caseResult.getBaselineConversionRate());
        response.setWinningConversionRate(caseResult.getWinningConversionRate());
        response.setStatisticsUrl(caseResult.getStatisticsUrl());
        response.setCompareUrl(caseResult.getCompareUrl());
        response.setBayesianUrl(caseResult.getBayesianUrl());
        response.setEarlyStopUrl(caseResult.getEarlyStopUrl());
        response.setAutoGraduateUrl(caseResult.getAutoGraduateUrl());
        return response;
    }

    @Data
    private static class ExistingExperimentDataGenerateRequest {
        private Integer visitorCount;
        private Integer daysAgo;
    }
}
