package com.pisces.service.service;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.request.AIDesignRequest;
import com.pisces.common.response.AIDesignResponse;
import com.pisces.common.response.AIDiagnosisResponse;
import com.pisces.common.response.AIGraduationDecisionResponse;

/**
 * AI决策服务
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:48
 */
public interface AIDecisionService {

    /**
     * 设计实验
     *
     * @param request 实验设计请求
     * @return 实验设计响应
     */
    AIDesignResponse designExperiment(AIDesignRequest request);

    /**
     * 诊断实验
     *
     * @param experimentId 实验ID
     * @return 实验诊断响应
     */
    AIDiagnosisResponse diagnoseExperiment(String experimentId);

    /**
     * 决策毕业
     *
     * @param experimentId 实验ID
     * @return 毕业决策响应
     */
    AIGraduationDecisionResponse decideGraduation(String experimentId);

    /**
     * 基于已有上下文决策毕业
     *
     * @param context 实验决策上下文
     * @return 毕业决策响应
     */
    AIGraduationDecisionResponse decideGraduation(ExperimentDecisionContext context);
}
