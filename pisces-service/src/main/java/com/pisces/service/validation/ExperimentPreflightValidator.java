package com.pisces.service.validation;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.model.TrafficConfig;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.common.response.ExperimentPreflightResponse;
import com.pisces.service.exception.BusinessException;
import com.pisces.service.rule.TrafficRuleEvaluator;
import com.pisces.service.schema.GroupConfigSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 实验创建前检查器
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/8/6 14:32
 */
@Component
@RequiredArgsConstructor
public class ExperimentPreflightValidator {

    public static final String STATUS_PASS = "PASS";

    public static final String STATUS_WARNING = "WARNING";

    public static final String STATUS_BLOCKED = "BLOCKED";

    private static final Pattern DEFINITION_KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private static final double TRAFFIC_RATIO_TOLERANCE = 0.001D;

    private static final int MAX_EXPERIMENT_NAME_LENGTH = 100;

    private final GroupConfigSchemaValidator groupConfigSchemaValidator;

    private final TrafficRuleEvaluator trafficRuleEvaluator;

    /**
     * 汇总实验草案的结构检查结果。
     *
     * @param request 实验草案
     * @param normalizedSchema 已归一化字段定义
     * @return 检查结果
     */
    public List<ExperimentPreflightResponse.CheckItem> validate(
            ExperimentCreateRequest request, List<GroupConfigFieldDefinition> normalizedSchema) {
        List<ExperimentPreflightResponse.CheckItem> checks = new ArrayList<>();
        checks.add(validateBasicInformation(request));
        checks.add(validateGroups(request, normalizedSchema));
        checks.add(validateTraffic(request));
        checks.add(validateEvents(request));
        checks.add(validateMetrics(request));
        checks.add(validateGuardrailMetric(request));
        return checks;
    }

    /**
     * 对创建入口执行共享阻断校验。
     *
     * @param checks 检查结果
     */
    public void assertReady(List<ExperimentPreflightResponse.CheckItem> checks) {
        checks.stream()
                .filter(check -> STATUS_BLOCKED.equals(check.getStatus()))
                .findFirst()
                .ifPresent(check -> {
                    throw new BusinessException(ResponseCode.VALIDATION_ERROR, check.getDetail());
                });
    }

    private ExperimentPreflightResponse.CheckItem validateBasicInformation(ExperimentCreateRequest request) {
        if (request == null) {
            return blocked("BASIC_INFORMATION", "基础信息", "实验信息不完整", "实验草案不能为空",
                    "请重新填写实验方案", "basics");
        }
        List<String> issues = new ArrayList<>();
        if (!StringUtils.hasText(request.getName())) {
            issues.add("请填写实验名称");
        } else if (request.getName().trim().length() > MAX_EXPERIMENT_NAME_LENGTH) {
            issues.add("实验名称不能超过100个字符");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            issues.add("请填写完整的开始时间和结束时间");
        } else if (!request.getEndTime().isAfter(request.getStartTime())) {
            issues.add("结束时间必须晚于开始时间");
        }
        return issues.isEmpty()
                ? passed("BASIC_INFORMATION", "基础信息", "基础信息完整", "应用、名称和实验周期已填写", "basics")
                : blocked("BASIC_INFORMATION", "基础信息", "基础信息需要补充", String.join("；", issues),
                        "请补充应用、名称和实验周期", "basics");
    }

    private ExperimentPreflightResponse.CheckItem validateGroups(
            ExperimentCreateRequest request, List<GroupConfigFieldDefinition> normalizedSchema) {
        List<ExperimentCreateRequest.GroupConfig> groups = request == null ? null : request.getGroups();
        if (groups == null || groups.size() < 2) {
            return blocked("GROUP_CONFIGURATION", "字段与分组", "实验组配置不完整", "至少需要两个实验组",
                    "请配置对照组和至少一个实验组", "groups");
        }
        List<String> issues = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        for (ExperimentCreateRequest.GroupConfig group : groups) {
            if (group == null || !StringUtils.hasText(group.getId())) {
                issues.add("存在未填写标识的实验组");
                continue;
            }
            String groupId = group.getId().trim();
            if (!groupIds.add(groupId)) {
                issues.add("实验组标识重复：" + groupId);
            }
            if (!StringUtils.hasText(group.getName())) {
                issues.add("实验组“" + groupId + "”缺少名称");
            }
            try {
                groupConfigSchemaValidator.normalizeGroupConfig(normalizedSchema, group.getConfig(), groupId);
            } catch (BusinessException exception) {
                issues.add(exception.getMessage());
            }
        }
        return issues.isEmpty()
                ? passed("GROUP_CONFIGURATION", "字段与分组", "字段与分组完整",
                        "已配置" + groups.size() + "个实验组", "groups")
                : blocked("GROUP_CONFIGURATION", "字段与分组", "字段或分组需要修正",
                        String.join("；", issues), "请修正实验组及其必填字段", "groups");
    }

    private ExperimentPreflightResponse.CheckItem validateTraffic(ExperimentCreateRequest request) {
        ExperimentCreateRequest.TrafficConfigRequest traffic = request == null ? null : request.getTraffic();
        if (traffic == null) {
            return blocked("TRAFFIC_CONFIGURATION", "流量", "流量配置缺失", "请配置实验总流量和组间分配",
                    "请完成流量策略配置", "groups");
        }
        List<String> issues = new ArrayList<>();
        Set<String> groupIds = new HashSet<>();
        if (request.getGroups() != null) {
            request.getGroups().stream()
                    .filter(group -> group != null && StringUtils.hasText(group.getId()))
                    .forEach(group -> groupIds.add(group.getId().trim()));
        }
        if (traffic.getTotalTraffic() == null
                || traffic.getTotalTraffic() < 0D
                || traffic.getTotalTraffic() > 1D) {
            issues.add("总流量必须在0%到100%之间");
        }
        List<ExperimentCreateRequest.GroupAllocationRequest> allocations = traffic.getAllocation();
        if (allocations == null || allocations.isEmpty()) {
            issues.add("请配置组间流量分配");
        } else {
            double totalRatio = 0D;
            Set<String> allocationGroups = new HashSet<>();
            for (ExperimentCreateRequest.GroupAllocationRequest allocation : allocations) {
                if (allocation == null || !StringUtils.hasText(allocation.getGroup())) {
                    issues.add("存在未选择实验组的流量分配");
                    continue;
                }
                String groupId = allocation.getGroup().trim();
                if (!allocationGroups.add(groupId)) {
                    issues.add("实验组“" + groupId + "”被重复分配流量");
                }
                if (!groupIds.contains(groupId)) {
                    issues.add("流量分配引用了不存在的实验组“" + groupId + "”");
                }
                if (allocation.getRatio() == null || allocation.getRatio() < 0D || allocation.getRatio() > 1D) {
                    issues.add("实验组“" + groupId + "”的流量比例必须在0%到100%之间");
                } else {
                    totalRatio += allocation.getRatio();
                }
            }
            if (Math.abs(totalRatio - 1D) > TRAFFIC_RATIO_TOLERANCE) {
                issues.add(String.format(Locale.ROOT, "组间流量合计必须为100%%，当前为%.1f%%", totalRatio * 100D));
            }
        }
        try {
            TrafficConfig trafficConfig = toTrafficConfig(traffic);
            trafficRuleEvaluator.validateRules(trafficConfig, groupIds);
        } catch (BusinessException | IllegalArgumentException exception) {
            issues.add(exception.getMessage());
        }
        return issues.isEmpty()
                ? passed("TRAFFIC_CONFIGURATION", "流量", "流量配置有效", "总流量和组间分配符合要求", "groups")
                : blocked("TRAFFIC_CONFIGURATION", "流量", "流量配置需要修正", String.join("；", issues),
                        "请修正流量比例或分流规则", "groups");
    }

    private ExperimentPreflightResponse.CheckItem validateEvents(ExperimentCreateRequest request) {
        List<EventDefinition> events = request == null ? null : request.getEventDefinitions();
        if (events == null || events.isEmpty()) {
            return blocked("EVENT_SELECTION", "事件", "尚未选择事件", "至少需要选择一个应用事件",
                    "请从应用字典中选择本实验使用的事件", "events");
        }
        List<String> issues = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int primaryCount = 0;
        for (EventDefinition event : events) {
            String key = normalizeDefinitionKey(event == null ? null : event.getKey());
            if (key == null) {
                issues.add("存在无效的事件编码");
                continue;
            }
            if (!keys.add(key)) {
                issues.add("事件编码重复：" + key);
            }
            if (!StringUtils.hasText(event.getLabel())) {
                issues.add("事件“" + key + "”缺少名称");
            }
            if (Boolean.TRUE.equals(event.getPrimary())) {
                primaryCount++;
            }
        }
        if (primaryCount > 1) {
            issues.add("最多只能选择一个主事件");
        }
        return issues.isEmpty()
                ? passed("EVENT_SELECTION", "事件", "事件选择有效", "已选择" + events.size() + "个事件", "events")
                : blocked("EVENT_SELECTION", "事件", "事件选择需要修正", String.join("；", issues),
                        "请修正事件选择", "events");
    }

    private ExperimentPreflightResponse.CheckItem validateMetrics(ExperimentCreateRequest request) {
        List<MetricDefinition> metrics = request == null ? null : request.getMetricDefinitions();
        if (metrics == null || metrics.isEmpty()) {
            return blocked("METRIC_SELECTION", "指标", "尚未选择指标", "至少需要选择一个应用指标",
                    "请从应用字典中选择本实验使用的指标", "metrics");
        }
        Set<String> eventKeys = new HashSet<>();
        if (request.getEventDefinitions() != null) {
            request.getEventDefinitions().stream()
                    .map(EventDefinition::getKey)
                    .map(this::normalizeDefinitionKey)
                    .filter(key -> key != null)
                    .forEach(eventKeys::add);
        }
        List<String> issues = new ArrayList<>();
        Set<String> metricKeys = new HashSet<>();
        int primaryCount = 0;
        for (MetricDefinition metric : metrics) {
            String metricKey = normalizeDefinitionKey(metric == null ? null : metric.getKey());
            if (metricKey == null) {
                issues.add("存在无效的指标编码");
                continue;
            }
            if (!metricKeys.add(metricKey)) {
                issues.add("指标编码重复：" + metricKey);
            }
            if (!StringUtils.hasText(metric.getName())) {
                issues.add("指标“" + metricKey + "”缺少名称");
            }
            validateMetricEventReferences(metric, metricKey, eventKeys, issues);
            if (Boolean.TRUE.equals(metric.getPrimaryMetric())) {
                primaryCount++;
            }
        }
        if (primaryCount != 1) {
            issues.add(primaryCount == 0 ? "必须选择一个主指标" : "只能选择一个主指标");
        }
        return issues.isEmpty()
                ? passed("METRIC_SELECTION", "指标", "指标口径有效", "已选择" + metrics.size() + "个指标", "metrics")
                : blocked("METRIC_SELECTION", "指标", "指标口径需要修正", String.join("；", issues),
                        "请修正主指标和事件引用", "metrics");
    }

    private ExperimentPreflightResponse.CheckItem validateGuardrailMetric(ExperimentCreateRequest request) {
        boolean hasGuardrail = request != null && request.getMetricDefinitions() != null
                && request.getMetricDefinitions().stream()
                .anyMatch(metric -> metric != null && Boolean.TRUE.equals(metric.getGuardrailMetric()));
        return hasGuardrail
                ? passed("GUARDRAIL_METRIC", "指标", "已配置护栏指标", "实验风险可通过护栏指标持续观察", "metrics")
                : warning("GUARDRAIL_METRIC", "指标", "尚未配置护栏指标", "实验仍可创建，但缺少风险指标保护",
                        "建议至少选择一个护栏指标", "metrics");
    }

    private void validateMetricEventReferences(MetricDefinition metric, String metricKey,
                                               Set<String> eventKeys, List<String> issues) {
        if (metric.getAggregationType() == null) {
            issues.add("指标“" + metricKey + "”缺少计算方式");
            return;
        }
        String numerator = normalizeDefinitionKey(metric.getNumeratorEventType());
        if (numerator == null || !eventKeys.contains(numerator)) {
            issues.add("指标“" + metricKey + "”依赖的分子事件未选择");
        }
        if (metric.getAggregationType() == MetricDefinition.AggregationType.RATE
                && metric.getDenominatorType() == null) {
            issues.add("比率指标“" + metricKey + "”缺少分母口径");
        }
        if (metric.getAggregationType() == MetricDefinition.AggregationType.RATE
                && metric.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT) {
            String denominator = normalizeDefinitionKey(metric.getDenominatorEventType());
            if (denominator == null || !eventKeys.contains(denominator)) {
                issues.add("指标“" + metricKey + "”依赖的分母事件未选择");
            }
        }
    }

    private TrafficConfig toTrafficConfig(ExperimentCreateRequest.TrafficConfigRequest request) {
        TrafficConfig trafficConfig = new TrafficConfig();
        trafficConfig.setTotalTraffic(request.getTotalTraffic());
        if (StringUtils.hasText(request.getStrategy())) {
            trafficConfig.setStrategy(TrafficConfig.TrafficStrategy.ofOrThrow(request.getStrategy()));
        }
        if (StringUtils.hasText(request.getRuleFallbackStrategy())) {
            trafficConfig.setRuleFallbackStrategy(
                    TrafficConfig.RuleFallbackStrategy.ofOrThrow(request.getRuleFallbackStrategy()));
        }
        trafficConfig.setHashKey(request.getHashKey());
        if (request.getRules() != null) {
            trafficConfig.setRules(request.getRules().stream().map(ruleRequest -> {
                if (ruleRequest == null) {
                    throw new IllegalArgumentException("规则配置不能为空");
                }
                TrafficConfig.TrafficRule rule = new TrafficConfig.TrafficRule();
                rule.setName(ruleRequest.getName());
                rule.setPriority(ruleRequest.getPriority());
                rule.setGroup(ruleRequest.getGroup());
                if (ruleRequest.getConditions() != null) {
                    rule.setConditions(ruleRequest.getConditions().stream().map(conditionRequest -> {
                        if (conditionRequest == null) {
                            throw new IllegalArgumentException("规则条件不能为空");
                        }
                        TrafficConfig.RuleCondition condition = new TrafficConfig.RuleCondition();
                        condition.setField(conditionRequest.getField());
                        if (StringUtils.hasText(conditionRequest.getOperator())) {
                            condition.setOperator(TrafficConfig.RuleOperator.ofOrThrow(conditionRequest.getOperator()));
                        }
                        condition.setValue(conditionRequest.getValue());
                        condition.setValues(conditionRequest.getValues());
                        return condition;
                    }).toList());
                }
                return rule;
            }).toList());
        }
        return trafficConfig;
    }

    private String normalizeDefinitionKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
        return DEFINITION_KEY_PATTERN.matcher(normalizedKey).matches() ? normalizedKey : null;
    }

    private ExperimentPreflightResponse.CheckItem passed(
            String code, String section, String title, String detail, String targetPanel) {
        return check(code, section, STATUS_PASS, title, detail, null, targetPanel);
    }

    private ExperimentPreflightResponse.CheckItem warning(
            String code, String section, String title, String detail, String action, String targetPanel) {
        return check(code, section, STATUS_WARNING, title, detail, action, targetPanel);
    }

    private ExperimentPreflightResponse.CheckItem blocked(
            String code, String section, String title, String detail, String action, String targetPanel) {
        return check(code, section, STATUS_BLOCKED, title, detail, action, targetPanel);
    }

    private ExperimentPreflightResponse.CheckItem check(
            String code, String section, String status, String title, String detail, String action,
            String targetPanel) {
        ExperimentPreflightResponse.CheckItem item = new ExperimentPreflightResponse.CheckItem();
        item.setCode(code);
        item.setSection(section);
        item.setStatus(status);
        item.setTitle(title);
        item.setDetail(detail);
        item.setAction(action);
        item.setTargetPanel(targetPanel);
        return item;
    }
}
