package com.pisces.api.experiment.response;

import lombok.Data;

/**
 * 实验演示响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/19 15:11
 */
@Data
public class ExperimentDemoResponse {

    private ExperimentCaseResponse qualifiedExperiment;

    private ExperimentCaseResponse unqualifiedExperiment;

    @Data
    public static class ExperimentCaseResponse {
        private String experimentId;
        private String experimentName;
        private String demoTag;
        private String baselineGroupId;
        private String winningGroupId;
        private Boolean canGraduate;
        private Boolean canStop;
        private Double baselineConversionRate;
        private Double winningConversionRate;
        private String statisticsUrl;
        private String compareUrl;
        private String bayesianUrl;
        private String earlyStopUrl;
        private String autoGraduateUrl;
    }
}
