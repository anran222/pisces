package com.pisces.common.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用接入检查响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/8/6 14:32
 */
@Data
public class ApplicationIntegrationHealthResponse {

    private String appId;

    private String displayName;

    private String status;

    private LocalDateTime generatedAt;

    private Integer eventDefinitionCount;

    private Integer metricDefinitionCount;

    private Integer experimentCount;

    private Integer runningExperimentCount;

    private Long assignmentCount;

    private Long exposureCount;

    private Long eventCount;

    private LocalDateTime latestActivityAt;

    private List<CheckItem> checks;

    /**
     * 应用接入检查项
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/8/6 14:32
     */
    @Data
    public static class CheckItem {

        private String code;

        private String status;

        private String title;

        private String detail;

        private String action;

        private Long evidenceCount;

        private String target;
    }
}
