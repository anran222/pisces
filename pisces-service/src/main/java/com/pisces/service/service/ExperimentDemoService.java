package com.pisces.service.service;

import lombok.Data;

/**
 * 实验演示服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/19 15:11
 */
public interface ExperimentDemoService {

    /**
     * 生成二手手机售卖演示实验。
     *
     * @return 演示实验结果
     */
    ExperimentDemoResult generateUsedPhoneDemo();

    @Data
    class ExperimentDemoResult {
        private ExperimentCaseResult qualifiedExperiment;
        private ExperimentCaseResult unqualifiedExperiment;
    }

    @Data
    class ExperimentCaseResult {
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
