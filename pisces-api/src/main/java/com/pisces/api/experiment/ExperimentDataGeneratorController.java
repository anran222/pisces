package com.pisces.api.experiment;

import com.pisces.common.response.BaseResponse;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.service.ExperimentDataGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 实验数据生成控制器
 * 用于快速生成完整的实验流程数据，方便测试
 */
@RestController
@RequestMapping("/experiments/generator")
@NoTokenRequired
public class ExperimentDataGeneratorController {
    
    @Autowired
    private ExperimentDataGeneratorService generatorService;
    
    /**
     * 生成完整的实验流程数据（自定义参数）
     * 
     * @param request 生成参数
     * @return 生成的实验ID
     */
    @PostMapping("/generate")
    public BaseResponse<Map<String, Object>> generateExperimentData(
            @RequestBody(required = false) GenerateRequest request) {
        
        // 使用默认参数如果未提供
        if (request == null) {
            request = new GenerateRequest();
        }
        
        String experimentId = generatorService.generateCompleteExperimentData(
                request.getExperimentName() != null ? request.getExperimentName() 
                        : "二手手机交易价格提升实验（自动生成）",
                request.getVisitorCount() != null ? request.getVisitorCount() : 100,
                request.getDaysAgo() != null ? request.getDaysAgo() : 7
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        result.put("experimentName", request.getExperimentName());
        result.put("visitorCount", request.getVisitorCount() != null ? request.getVisitorCount() : 100);
        result.put("totalVisitors", (request.getVisitorCount() != null ? request.getVisitorCount() : 100) * 4);
        result.put("message", "实验数据生成成功！可以调用 /api/analysis/experiment/" + experimentId + "/statistics 查看统计数据");
        
        return BaseResponse.of("实验数据生成成功", result);
    }
    
    /**
     * 快速生成默认实验数据（使用默认参数）
     * 
     * @return 生成的实验ID
     */
    @PostMapping("/generate/default")
    public BaseResponse<Map<String, Object>> generateDefaultExperimentData() {
        String experimentId = generatorService.generateDefaultExperimentData();
        
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        result.put("experimentName", "二手手机交易价格提升实验（自动生成）");
        result.put("visitorCount", 100);
        result.put("totalVisitors", 400); // 4个实验组
        result.put("message", "实验数据生成成功！可以调用 /api/analysis/experiment/" + experimentId + "/statistics 查看统计数据");
        
        return BaseResponse.of("默认实验数据生成成功", result);
    }
    
    /**
     * 快速生成实验数据（使用推荐参数）
     * 
     * 推荐参数：
     * - 实验名称：二手手机交易价格提升实验
     * - 每个实验组访客数：200（总访客数800）
     * - 实验开始时间：14天前
     * 
     * @return 生成的实验ID和统计信息
     */
    @PostMapping("/generate/quick")
    public BaseResponse<Map<String, Object>> generateQuickExperimentData() {
        String experimentId = generatorService.generateCompleteExperimentData(
                "二手手机交易价格提升实验",
                200,  // 每个组200个访客，总800个访客
                14    // 14天前开始
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("experimentId", experimentId);
        result.put("experimentName", "二手手机交易价格提升实验");
        result.put("visitorCount", 200);
        result.put("totalVisitors", 800); // 4个实验组 × 200
        result.put("daysAgo", 14);
        result.put("experimentDuration", "14天");
        result.put("message", "实验数据生成成功！可以调用 /api/analysis/experiment/" + experimentId + "/statistics 查看统计数据");
        
        return BaseResponse.of("实验数据生成成功", result);
    }
    
    /**
     * 生成请求参数
     */
    public static class GenerateRequest {
        private String experimentName;
        private Integer visitorCount;  // 每个实验组的访客数
        private Integer daysAgo;       // 实验开始时间（几天前）
        
        public String getExperimentName() {
            return experimentName;
        }
        
        public void setExperimentName(String experimentName) {
            this.experimentName = experimentName;
        }
        
        public Integer getVisitorCount() {
            return visitorCount;
        }
        
        public void setVisitorCount(Integer visitorCount) {
            this.visitorCount = visitorCount;
        }
        
        public Integer getDaysAgo() {
            return daysAgo;
        }
        
        public void setDaysAgo(Integer daysAgo) {
            this.daysAgo = daysAgo;
        }
    }
}
