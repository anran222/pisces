package com.pisces.api.experiment;

import com.pisces.api.experiment.response.ExperimentDemoResponse;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.ExperimentDemoService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final ExperimentDemoService experimentDemoService;

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
        response.setBaselineConversionRate(caseResult.getBaselineConversionRate());
        response.setWinningConversionRate(caseResult.getWinningConversionRate());
        response.setStatisticsUrl(caseResult.getStatisticsUrl());
        response.setCompareUrl(caseResult.getCompareUrl());
        response.setBayesianUrl(caseResult.getBayesianUrl());
        response.setEarlyStopUrl(caseResult.getEarlyStopUrl());
        response.setAutoGraduateUrl(caseResult.getAutoGraduateUrl());
        return response;
    }
}
