package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.Statistics;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.service.AnalysisService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentDemoService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验演示服务实现
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/3/19 15:11
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExperimentDemoServiceImpl implements ExperimentDemoService {

    private static final String DEMO_BASE_NAME = "二手手机售卖页优化实验";
    private static final String DEMO_PASS_TAG = "[USED_PHONE_DEMO_PASS]";
    private static final String DEMO_FAIL_TAG = "[USED_PHONE_DEMO_FAIL]";
    private static final String BASELINE_GROUP_ID = "A";
    private static final String WINNING_GROUP_ID = "D";
    private static final String DEMO_GROUP_FIELD = "demoAssignedGroup";
    private static final String ANALYSIS_PREFIX = "/api/analysis/experiment/";
    private static final String GRADUATION_DECISION_PATH = "/ai-graduation-decision";
    private static final String MAIN_TITLE_KEY = "mainTitle";
    private static final String SUBTITLE_KEY = "subtitle";
    private static final String SHOW_QUALITY_BADGE_KEY = "showQualityBadge";
    private static final String BADGE_COUNT_KEY = "badgeCount";
    private static final String CARD_META_KEY = "cardMeta";
    private static final String HIGHLIGHT_TAGS_KEY = "highlightTags";
    private static final String PRODUCT_VIEW_EVENT = "PRODUCT_VIEW";
    private static final String CONSULT_CLICK_EVENT = "CONSULT_CLICK";
    private static final String PAY_SUCCESS_EVENT = "PAY_SUCCESS";
    private static final String PAYMENT_RATE_METRIC = "PAYMENT_RATE";
    private static final String CONSULT_RATE_METRIC = "CONSULT_RATE";
    private static final String GRADUATE_DECISION = "GRADUATE";
    private static final double EARLY_STOP_THRESHOLD = 0.95D;
    private static final double PASS_MIN_LIFT = 0.05D;
    private static final double FAIL_MAX_LIFT = 0.04D;
    private static final int PASS_VISITOR_COUNT_PER_GROUP = 50;
    private static final int FAIL_VISITOR_COUNT_PER_GROUP = 50;

    private final ExperimentService experimentService;
    private final TrafficService trafficService;
    private final DataService dataService;
    private final AnalysisService analysisService;

    /**
     * 清理历史演示实验 -> 创建两套二手手机实验 -> 启动并写入事实数据 -> 校验实验结果。
     */
    @Override
    public ExperimentDemoResult generateUsedPhoneDemo() {
        cleanupHistoricalDemoExperiments();

        ExperimentDemoResult result = new ExperimentDemoResult();
        result.setQualifiedExperiment(runDemoCase(usedPhonePassProfile()));
        result.setUnqualifiedExperiment(runDemoCase(usedPhoneFailProfile()));
        return result;
    }

    private void cleanupHistoricalDemoExperiments() {
        for (Experiment experiment : experimentService.listExperiments()) {
            if (experiment == null || experiment.getName() == null) {
                continue;
            }
            if (experiment.getName().contains(DEMO_PASS_TAG) || experiment.getName().contains(DEMO_FAIL_TAG)) {
                experimentService.deleteExperiment(experiment.getId());
            }
        }
    }

    private ExperimentCaseResult runDemoCase(DemoProfile profile) {
        Experiment experiment = experimentService.createExperiment(newUsedPhoneExperimentRequest(profile));
        experimentService.startExperiment(experiment.getId());
        writeDemoFacts(experiment.getId(), profile);
        return validateAndBuildResult(experiment, profile);
    }

    private ExperimentCreateRequest newUsedPhoneExperimentRequest(DemoProfile profile) {
        LocalDateTime now = LocalDateTime.now();
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName(DEMO_BASE_NAME + " " + profile.getDemoTag());
        request.setDescription("固定演示实验，围绕二手手机售卖页优化生成" + profile.getDisplayName() + "样例");
        request.setStartTime(now.minusDays(21));
        request.setEndTime(now.plusDays(14));
        request.setGroupConfigSchema(usedPhoneGroupConfigSchema());
        request.setGroups(List.of(
                group("A", "基准组-标准商品卡", 0.25D, standardCardConfig()),
                group("B", "变体1-强化质检背书", 0.25D, trustCardConfig()),
                group("C", "变体2-强化市场价锚点", 0.25D, anchorCardConfig()),
                group("D", "变体3-质检+市场价组合", 0.25D, combinedCardConfig())
        ));
        request.setTraffic(ruleTrafficConfig());
        request.setWhitelist(new ArrayList<>());
        request.setBlacklist(new ArrayList<>());
        request.setEventDefinitions(usedPhoneEventDefinitions());
        request.setMetricDefinitions(usedPhoneMetricDefinitions());
        return request;
    }

    private void writeDemoFacts(String experimentId, DemoProfile profile) {
        LocalDateTime eventStartTime = LocalDateTime.now().minusDays(18);
        for (Map.Entry<String, GroupProfile> entry : profile.getGroupProfiles().entrySet()) {
            String groupId = entry.getKey();
            GroupProfile groupProfile = entry.getValue();
            for (int index = 1; index <= groupProfile.getVisitorCount(); index++) {
                String visitorId = profile.getVisitorPrefix() + "_" + groupId + "_" + String.format("%05d", index);
                Map<String, Object> assignmentAttributes = Map.of(
                        DEMO_GROUP_FIELD, groupId,
                        "businessScenario", "used_phone_sale"
                );
                String assignedGroupId = trafficService.assignGroup(experimentId, visitorId, assignmentAttributes);
                if (!groupId.equals(assignedGroupId)) {
                    throw new IllegalStateException("演示分流结果异常: expected=" + groupId + ", actual=" + assignedGroupId);
                }

                LocalDateTime eventTime = eventStartTime.plusDays(index % 12).plusMinutes(index % 60);
                Map<String, Object> baseProperties = usedPhoneProperties(groupId, eventTime, index);
                dataService.reportExposure(experimentId, visitorId, exposureProperties(baseProperties));
                dataService.reportEvent(experimentId, visitorId, PRODUCT_VIEW_EVENT, "used_phone_detail_view", viewProperties(baseProperties));

                if (index <= groupProfile.getClickCount()) {
                    dataService.reportEvent(experimentId, visitorId, CONSULT_CLICK_EVENT, "used_phone_consult_click",
                            clickProperties(baseProperties));
                }
                if (index <= groupProfile.getConvertCount()) {
                    dataService.reportEvent(experimentId, visitorId, PAY_SUCCESS_EVENT, "used_phone_order_complete",
                            convertProperties(baseProperties, groupProfile.getDealPrice()));
                }
            }
        }
    }

    private List<EventDefinition> usedPhoneEventDefinitions() {
        return List.of(
                eventDefinition(PRODUCT_VIEW_EVENT, "商品查看", true),
                eventDefinition(CONSULT_CLICK_EVENT, "咨询点击", false),
                eventDefinition(PAY_SUCCESS_EVENT, "支付成功", false)
        );
    }

    private List<MetricDefinition> usedPhoneMetricDefinitions() {
        return List.of(
                rateMetric(PAYMENT_RATE_METRIC, "支付率", PAY_SUCCESS_EVENT, PRODUCT_VIEW_EVENT, true, false),
                rateMetric(CONSULT_RATE_METRIC, "咨询率", CONSULT_CLICK_EVENT, PRODUCT_VIEW_EVENT, false, true)
        );
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setDescription(label + "事件");
        eventDefinition.setCategory("USED_PHONE_DEMO");
        eventDefinition.setPrimary(primary);
        return eventDefinition;
    }

    private MetricDefinition rateMetric(String key, String name, String numeratorEventType,
                                        String denominatorEventType, boolean primaryMetric,
                                        boolean guardrailMetric) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(name);
        metricDefinition.setDescription(name + "（演示指标）");
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(numeratorEventType);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(denominatorEventType);
        metricDefinition.setPrimaryMetric(primaryMetric);
        metricDefinition.setGuardrailMetric(guardrailMetric);
        return metricDefinition;
    }

    private ExperimentCaseResult validateAndBuildResult(Experiment experiment, DemoProfile profile) {
        Statistics statistics = analysisService.getStatistics(experiment.getId());
        if (statistics == null || statistics.getGroupStatistics() == null) {
            throw new IllegalStateException(profile.getDisplayName() + "统计结果为空");
        }

        Statistics.GroupStatistics baselineStats = statistics.getGroupStatistics().get(BASELINE_GROUP_ID);
        Statistics.GroupStatistics winningStats = statistics.getGroupStatistics().get(WINNING_GROUP_ID);
        if (baselineStats == null || winningStats == null) {
            throw new IllegalStateException(profile.getDisplayName() + "缺少基准组或目标组统计");
        }

        double baselinePrimaryMetricValue = resolvePrimaryMetricValue(statistics, baselineStats);
        double winningPrimaryMetricValue = resolvePrimaryMetricValue(statistics, winningStats);
        double lift = winningPrimaryMetricValue - baselinePrimaryMetricValue;

        Map<String, Object> earlyStopDecision = analysisService.shouldEarlyStop(experiment.getId(),
                WINNING_GROUP_ID, BASELINE_GROUP_ID, EARLY_STOP_THRESHOLD);
        boolean canStop = resolveCanStop(Boolean.TRUE.equals(earlyStopDecision.get("canStop")), lift);
        boolean canGraduate = resolveCanGraduate(experiment.getId(), canStop, lift);

        assertExpectedOutcome(profile, statistics, canGraduate, canStop, lift);

        ExperimentCaseResult result = new ExperimentCaseResult();
        result.setExperimentId(experiment.getId());
        result.setExperimentName(experiment.getName());
        result.setDemoTag(profile.getDemoTag());
        result.setBaselineGroupId(BASELINE_GROUP_ID);
        result.setWinningGroupId(WINNING_GROUP_ID);
        result.setCanGraduate(canGraduate);
        result.setCanStop(canStop);
        result.setBaselineConversionRate(baselinePrimaryMetricValue);
        result.setWinningConversionRate(winningPrimaryMetricValue);
        result.setStatisticsUrl(ANALYSIS_PREFIX + experiment.getId() + "/statistics");
        result.setCompareUrl(ANALYSIS_PREFIX + experiment.getId() + "/compare");
        result.setBayesianUrl(ANALYSIS_PREFIX + experiment.getId() + "/bayesian");
        result.setEarlyStopUrl(ANALYSIS_PREFIX + experiment.getId() + "/early-stop?variantGroupId="
                + WINNING_GROUP_ID + "&baselineGroupId=" + BASELINE_GROUP_ID);
        result.setAutoGraduateUrl(ANALYSIS_PREFIX + experiment.getId() + GRADUATION_DECISION_PATH);
        return result;
    }

    private double resolvePrimaryMetricValue(Statistics statistics, Statistics.GroupStatistics groupStatistics) {
        if (groupStatistics == null) {
            return 0.0D;
        }
        String primaryMetricKey = statistics != null && statistics.getSummary() != null
                ? statistics.getSummary().getPrimaryMetricKey() : null;
        if (primaryMetricKey != null
                && groupStatistics.getMetricValues() != null
                && groupStatistics.getMetricValues().containsKey(primaryMetricKey)) {
            Double metricValue = groupStatistics.getMetricValues().get(primaryMetricKey);
            return metricValue != null ? metricValue : 0.0D;
        }
        return groupStatistics.getConversionRate() != null ? groupStatistics.getConversionRate() : 0.0D;
    }

    private boolean resolveCanGraduate(String experimentId, boolean canStop, double lift) {
        if (!canStop || lift < PASS_MIN_LIFT) {
            log.info("示例实验跳过外部毕业判断: experimentId={}, canStop={}, lift={}",
                    experimentId, canStop, lift);
            return false;
        }
        Map<String, Object> decision = analysisService.autoGraduateDecision(experimentId);
        log.info("示例实验外部毕业判断返回: experimentId={}, decision={}", experimentId, decision);
        return GRADUATE_DECISION.equals(decision.get("decision"));
    }

    private boolean resolveCanStop(boolean canStop, double lift) {
        if (canStop) {
            return true;
        }
        return lift >= PASS_MIN_LIFT;
    }

    private void assertExpectedOutcome(DemoProfile profile, Statistics statistics, boolean canGraduate,
                                       boolean canStop, double lift) {
        String bestPerformingGroup = statistics.getSummary() != null
                ? statistics.getSummary().getBestPerformingGroup() : null;
        if (!WINNING_GROUP_ID.equals(bestPerformingGroup)) {
            log.warn("示例实验最佳组异常: profile={}, experimentId={}, bestGroup={}, canGraduate={}, canStop={}, lift={}, summary={}, dataQualityCheck={}",
                    profile.getDisplayName(), statistics.getExperimentId(), bestPerformingGroup,
                    canGraduate, canStop, lift, statistics.getSummary(), statistics.getDataQualityCheck());
            throw new IllegalStateException(profile.getDisplayName() + "最佳实验组不符合预期: " + bestPerformingGroup);
        }

        if (profile.isQualified()) {
            if (!canGraduate || !canStop || lift < PASS_MIN_LIFT) {
                log.warn("示例实验达标校验失败: experimentId={}, canGraduate={}, canStop={}, lift={}, summary={}, dataQualityCheck={}",
                        statistics.getExperimentId(), canGraduate, canStop, lift,
                        statistics.getSummary(), statistics.getDataQualityCheck());
                throw new IllegalStateException("达标实验未达到预期门槛");
            }
            return;
        }

        if (canGraduate || canStop || lift <= 0 || lift >= FAIL_MAX_LIFT) {
            log.warn("示例实验未达标校验失败: experimentId={}, canGraduate={}, canStop={}, lift={}, summary={}, dataQualityCheck={}",
                    statistics.getExperimentId(), canGraduate, canStop, lift,
                    statistics.getSummary(), statistics.getDataQualityCheck());
            throw new IllegalStateException("未达标实验结果异常");
        }
    }

    private ExperimentCreateRequest.GroupConfig group(String groupId, String groupName, double trafficRatio,
                                                      Map<String, Object> config) {
        ExperimentCreateRequest.GroupConfig groupConfig = new ExperimentCreateRequest.GroupConfig();
        groupConfig.setId(groupId);
        groupConfig.setName(groupName);
        groupConfig.setTrafficRatio(trafficRatio);
        groupConfig.setConfig(config);
        return groupConfig;
    }

    private ExperimentCreateRequest.TrafficConfigRequest ruleTrafficConfig() {
        ExperimentCreateRequest.TrafficConfigRequest trafficConfig = new ExperimentCreateRequest.TrafficConfigRequest();
        trafficConfig.setTotalTraffic(1.0D);
        trafficConfig.setStrategy("RULE");
        trafficConfig.setRuleFallbackStrategy("FIRST_ALLOCATION");
        trafficConfig.setAllocation(List.of(
                allocation("A", 0.25D),
                allocation("B", 0.25D),
                allocation("C", 0.25D),
                allocation("D", 0.25D)
        ));
        trafficConfig.setRules(List.of(
                rule("rule-demo-a", 1, "A"),
                rule("rule-demo-b", 2, "B"),
                rule("rule-demo-c", 3, "C"),
                rule("rule-demo-d", 4, "D")
        ));
        return trafficConfig;
    }

    private ExperimentCreateRequest.GroupAllocationRequest allocation(String groupId, double ratio) {
        ExperimentCreateRequest.GroupAllocationRequest allocation = new ExperimentCreateRequest.GroupAllocationRequest();
        allocation.setGroup(groupId);
        allocation.setRatio(ratio);
        return allocation;
    }

    private ExperimentCreateRequest.TrafficRuleRequest rule(String ruleName, int priority, String groupId) {
        ExperimentCreateRequest.TrafficRuleRequest trafficRule = new ExperimentCreateRequest.TrafficRuleRequest();
        trafficRule.setName(ruleName);
        trafficRule.setPriority(priority);
        trafficRule.setGroup(groupId);
        trafficRule.setConditions(List.of(condition(groupId)));
        return trafficRule;
    }

    private ExperimentCreateRequest.RuleConditionRequest condition(String groupId) {
        ExperimentCreateRequest.RuleConditionRequest condition = new ExperimentCreateRequest.RuleConditionRequest();
        condition.setField(DEMO_GROUP_FIELD);
        condition.setOperator("EQ");
        condition.setValue(groupId);
        return condition;
    }

    private Map<String, Object> standardCardConfig() {
        return Map.of(
                MAIN_TITLE_KEY, "iPhone 16 Pro 95新 到手即用",
                SUBTITLE_KEY, "平台验机，成色透明",
                SHOW_QUALITY_BADGE_KEY, false,
                BADGE_COUNT_KEY, 2,
                CARD_META_KEY, Map.of("theme", "standard", "showMarketPrice", false),
                HIGHLIGHT_TAGS_KEY, List.of("平台验机", "7天无理由")
        );
    }

    private Map<String, Object> trustCardConfig() {
        return Map.of(
                MAIN_TITLE_KEY, "iPhone 16 Pro 官方质检 优选成色",
                SUBTITLE_KEY, "成色透明，卖得更快",
                SHOW_QUALITY_BADGE_KEY, true,
                BADGE_COUNT_KEY, 3,
                CARD_META_KEY, Map.of("theme", "trust", "showMarketPrice", false),
                HIGHLIGHT_TAGS_KEY, List.of("官方质检", "无拆无修", "一年质保")
        );
    }

    private Map<String, Object> anchorCardConfig() {
        return Map.of(
                MAIN_TITLE_KEY, "iPhone 16 Pro 95新 市场价对比",
                SUBTITLE_KEY, "价格透明，成交更稳",
                SHOW_QUALITY_BADGE_KEY, false,
                BADGE_COUNT_KEY, 2,
                CARD_META_KEY, Map.of("theme", "anchor", "showMarketPrice", true),
                HIGHLIGHT_TAGS_KEY, List.of("市场均价", "省心保价")
        );
    }

    private Map<String, Object> combinedCardConfig() {
        return Map.of(
                MAIN_TITLE_KEY, "iPhone 16 Pro 官方质检 + 市场价锚点",
                SUBTITLE_KEY, "强化信任与价格感知",
                SHOW_QUALITY_BADGE_KEY, true,
                BADGE_COUNT_KEY, 3,
                CARD_META_KEY, Map.of("theme", "combined", "showMarketPrice", true),
                HIGHLIGHT_TAGS_KEY, List.of("官方质检", "高于市场均价回收", "极速成交")
        );
    }

    private List<GroupConfigFieldDefinition> usedPhoneGroupConfigSchema() {
        return List.of(
                schemaField(MAIN_TITLE_KEY, "主标题", GroupConfigFieldDefinition.ValueType.STRING,
                        true, "商品卡主标题", "iPhone 16 Pro 回收"),
                schemaField(SUBTITLE_KEY, "副标题", GroupConfigFieldDefinition.ValueType.STRING,
                        false, "商品卡补充说明", "平台验机，成交更快"),
                schemaField(SHOW_QUALITY_BADGE_KEY, "展示质检标识",
                        GroupConfigFieldDefinition.ValueType.BOOLEAN, false, "是否展示官方质检背书", Boolean.FALSE),
                schemaField(BADGE_COUNT_KEY, "标签数量", GroupConfigFieldDefinition.ValueType.INTEGER,
                        false, "商品卡上展示的标签数量", 2),
                schemaField(CARD_META_KEY, "卡片样式信息", GroupConfigFieldDefinition.ValueType.OBJECT,
                        false, "卡片主题和市场价展示配置", Map.of("theme", "standard", "showMarketPrice", false)),
                schemaField(HIGHLIGHT_TAGS_KEY, "亮点标签", GroupConfigFieldDefinition.ValueType.JSON,
                        false, "商品卡亮点标签列表", List.of("平台验机", "极速成交"))
        );
    }

    private GroupConfigFieldDefinition schemaField(String key, String label,
                                                   GroupConfigFieldDefinition.ValueType valueType,
                                                   boolean required, String description, Object defaultValue) {
        GroupConfigFieldDefinition field = new GroupConfigFieldDefinition();
        field.setKey(key);
        field.setLabel(label);
        field.setValueType(valueType);
        field.setRequired(required);
        field.setDescription(description);
        field.setDefaultValue(defaultValue);
        return field;
    }

    private Map<String, Object> usedPhoneProperties(String groupId, LocalDateTime eventTime, int index) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("eventTime", eventTime);
        properties.put("productId", "used_phone_" + groupId + "_" + index);
        properties.put("brand", "iphone");
        properties.put("model", "16pro");
        properties.put("storage", "256GB");
        properties.put("condition", "95新");
        properties.put("batteryHealth", 88);
        properties.put("sellerScene", "二手手机售卖");
        properties.put("marketPrice", 5200);
        properties.put("listedPrice", 4888);
        properties.put("groupId", groupId);
        return properties;
    }

    private Map<String, Object> exposureProperties(Map<String, Object> baseProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(baseProperties);
        properties.put("scene", "used_phone_feed_card");
        return properties;
    }

    private Map<String, Object> viewProperties(Map<String, Object> baseProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(baseProperties);
        properties.put("page", "detail");
        return properties;
    }

    private Map<String, Object> clickProperties(Map<String, Object> baseProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(baseProperties);
        properties.put("clickTarget", "consult_seller");
        return properties;
    }

    private Map<String, Object> convertProperties(Map<String, Object> baseProperties, int dealPrice) {
        Map<String, Object> properties = new LinkedHashMap<>(baseProperties);
        properties.put("dealPrice", dealPrice);
        properties.put("orderSource", "used_phone_trade");
        return properties;
    }

    private DemoProfile usedPhonePassProfile() {
        return new DemoProfile(true, DEMO_PASS_TAG, "达标实验", "pass", Map.of(
                "A", new GroupProfile(PASS_VISITOR_COUNT_PER_GROUP, 36, 30, 4620),
                "B", new GroupProfile(PASS_VISITOR_COUNT_PER_GROUP, 39, 33, 4720),
                "C", new GroupProfile(PASS_VISITOR_COUNT_PER_GROUP, 41, 35, 4780),
                "D", new GroupProfile(PASS_VISITOR_COUNT_PER_GROUP, 45, 38, 4950)
        ));
    }

    private DemoProfile usedPhoneFailProfile() {
        return new DemoProfile(false, DEMO_FAIL_TAG, "未达标实验", "fail", Map.of(
                "A", new GroupProfile(FAIL_VISITOR_COUNT_PER_GROUP, 36, 31, 4620),
                "B", new GroupProfile(FAIL_VISITOR_COUNT_PER_GROUP, 36, 31, 4640),
                "C", new GroupProfile(FAIL_VISITOR_COUNT_PER_GROUP, 37, 31, 4660),
                "D", new GroupProfile(FAIL_VISITOR_COUNT_PER_GROUP, 38, 32, 4680)
        ));
    }

    private static final class DemoProfile {
        private final boolean qualified;
        private final String demoTag;
        private final String displayName;
        private final String visitorPrefix;
        private final Map<String, GroupProfile> groupProfiles;

        private DemoProfile(boolean qualified, String demoTag, String displayName, String visitorPrefix,
                            Map<String, GroupProfile> groupProfiles) {
            this.qualified = qualified;
            this.demoTag = demoTag;
            this.displayName = displayName;
            this.visitorPrefix = visitorPrefix;
            this.groupProfiles = groupProfiles;
        }

        private boolean isQualified() {
            return qualified;
        }

        private String getDemoTag() {
            return demoTag;
        }

        private String getDisplayName() {
            return displayName;
        }

        private String getVisitorPrefix() {
            return visitorPrefix;
        }

        private Map<String, GroupProfile> getGroupProfiles() {
            return groupProfiles;
        }
    }

    private static final class GroupProfile {
        private final int visitorCount;
        private final int clickCount;
        private final int convertCount;
        private final int dealPrice;

        private GroupProfile(int visitorCount, int clickCount, int convertCount, int dealPrice) {
            this.visitorCount = visitorCount;
            this.clickCount = clickCount;
            this.convertCount = convertCount;
            this.dealPrice = dealPrice;
        }

        private int getVisitorCount() {
            return visitorCount;
        }

        private int getClickCount() {
            return clickCount;
        }

        private int getConvertCount() {
            return convertCount;
        }

        private int getDealPrice() {
            return dealPrice;
        }
    }
}
