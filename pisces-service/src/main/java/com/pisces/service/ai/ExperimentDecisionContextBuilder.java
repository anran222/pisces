package com.pisces.service.ai;

import com.pisces.common.model.ExperimentDecisionContext;
import com.pisces.common.model.Statistics;
import com.pisces.service.service.AnalysisService;
import org.springframework.stereotype.Service;

/**
 * 实验决策上下文构建器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/20 13:54
 */
@Service
public class ExperimentDecisionContextBuilder {

    private final AnalysisService analysisService;

    public ExperimentDecisionContextBuilder(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    public ExperimentDecisionContext buildForExperiment(String experimentId) {
        Statistics statistics = analysisService.getStatistics(experimentId);
        ExperimentDecisionContext context = new ExperimentDecisionContext();
        context.setExperimentId(extractExperimentId(experimentId, statistics));
        if (statistics != null) {
            context.setExperimentName(statistics.getExperimentName());
            context.setExperimentStatus(statistics.getExperimentStatus());
            context.setStatistics(statistics);
        }
        return context;
    }

    private String extractExperimentId(String experimentId, Statistics statistics) {
        if (statistics == null || statistics.getExperimentId() == null || statistics.getExperimentId().isBlank()) {
            return experimentId;
        }
        return statistics.getExperimentId();
    }
}
