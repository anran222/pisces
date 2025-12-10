package com.pisces.api.analysis;

import com.pisces.common.model.Statistics;
import com.pisces.common.response.BaseResponse;
import com.pisces.service.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据分析控制器
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
    public BaseResponse<Statistics> getStatistics(@PathVariable String id) {
        Statistics statistics = analysisService.getStatistics(id);
        return BaseResponse.of(statistics);
    }
    
    /**
     * 对比实验组
     */
    @GetMapping("/experiment/{id}/compare")
    public BaseResponse<Map<String, Object>> compareGroups(@PathVariable String id) {
        Map<String, Object> comparison = analysisService.compareGroups(id);
        return BaseResponse.of(comparison);
    }
}

