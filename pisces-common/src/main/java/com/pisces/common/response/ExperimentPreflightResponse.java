package com.pisces.common.response;

import lombok.Data;

import java.util.List;

/**
 * 实验创建前检查响应
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/8/6 14:32
 */
@Data
public class ExperimentPreflightResponse {

    private Boolean readyToCreate;

    private Integer blockingCount;

    private Integer warningCount;

    private List<CheckItem> checks;

    private Summary summary;

    private ApplicationGovernance applicationGovernance;

    /**
     * 实验创建前检查项
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/8/6 14:32
     */
    @Data
    public static class CheckItem {

        private String code;

        private String section;

        private String status;

        private String title;

        private String detail;

        private String action;

        private String targetPanel;
    }

    /**
     * 实验创建前检查摘要
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/8/6 14:32
     */
    @Data
    public static class Summary {

        private String appId;

        private String applicationName;

        private String experimentName;

        private String startTime;

        private String endTime;

        private Integer groupCount;

        private Double totalTraffic;

        private Integer eventCount;

        private Integer metricCount;

        private String primaryMetricKey;

        private String primaryMetricName;
    }

    /**
     * 应用治理检查摘要
     *
     * @author anran.xiang@atrenew.com
     * @date 2026/8/6 14:32
     */
    @Data
    public static class ApplicationGovernance {

        private Integer experimentQuota;

        private Integer quotaUsed;

        private Integer quotaRemaining;

        private Boolean approvalRequired;

        private Boolean releaseWindowEnabled;

        private Boolean currentlyInReleaseWindow;

        private String releaseWindowDescription;
    }
}
