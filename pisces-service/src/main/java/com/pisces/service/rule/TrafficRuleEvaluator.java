package com.pisces.service.rule;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.TrafficConfig;
import com.pisces.service.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则分流计算器
 */
@Component
public class TrafficRuleEvaluator {

    /**
     * 校验规则配置 -> 组装前置约束 -> 拒绝非法规则
     */
    public void validateRules(TrafficConfig trafficConfig, Set<String> groupIds) {
        if (trafficConfig == null || trafficConfig.getStrategy() != TrafficConfig.TrafficStrategy.RULE) {
            return;
        }
        if (CollectionUtils.isEmpty(trafficConfig.getRules())) {
            throw new BusinessException(ResponseCode.VALIDATION_ERROR, "RULE 策略必须配置至少一条规则");
        }

        Set<String> seenRuleNames = new HashSet<>();
        for (TrafficConfig.TrafficRule trafficRule : trafficConfig.getRules()) {
            if (trafficRule == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "规则配置不能为空");
            }
            if (!StringUtils.hasText(trafficRule.getGroup())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "规则命中的实验组不能为空");
            }
            if (!groupIds.contains(trafficRule.getGroup())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "规则命中的实验组不存在: " + trafficRule.getGroup());
            }
            if (StringUtils.hasText(trafficRule.getName()) && !seenRuleNames.add(trafficRule.getName())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "规则名称重复: " + trafficRule.getName());
            }
            if (CollectionUtils.isEmpty(trafficRule.getConditions())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "每条规则至少需要一个条件");
            }
            validateConditions(trafficRule.getConditions());
        }
    }

    /**
     * 计算规则命中结果 -> 按优先级依次匹配 -> 返回首个命中实验组
     */
    public String evaluateGroup(TrafficConfig trafficConfig, Map<String, Object> context) {
        if (trafficConfig == null || CollectionUtils.isEmpty(trafficConfig.getRules())) {
            return null;
        }

        List<TrafficConfig.TrafficRule> sortedRules = new ArrayList<>(trafficConfig.getRules());
        sortedRules.sort(Comparator.comparing(rule -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority()));
        for (TrafficConfig.TrafficRule trafficRule : sortedRules) {
            if (matchesAllConditions(trafficRule.getConditions(), context)) {
                return trafficRule.getGroup();
            }
        }
        return null;
    }

    private void validateConditions(List<TrafficConfig.RuleCondition> conditions) {
        for (TrafficConfig.RuleCondition condition : conditions) {
            if (condition == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "规则条件不能为空");
            }
            if (!StringUtils.hasText(condition.getField())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR, "规则条件字段不能为空");
            }
            if (condition.getOperator() == null) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "规则条件操作符不能为空: " + condition.getField());
            }
            if (condition.getOperator() == TrafficConfig.RuleOperator.IN
                    && CollectionUtils.isEmpty(condition.getValues())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "IN 操作符必须配置 values: " + condition.getField());
            }
            if (condition.getOperator() != TrafficConfig.RuleOperator.IN
                    && condition.getOperator() != TrafficConfig.RuleOperator.EXISTS
                    && !StringUtils.hasText(condition.getValue())) {
                throw new BusinessException(ResponseCode.VALIDATION_ERROR,
                        "规则条件值不能为空: " + condition.getField());
            }
        }
    }

    private boolean matchesAllConditions(List<TrafficConfig.RuleCondition> conditions, Map<String, Object> context) {
        for (TrafficConfig.RuleCondition condition : conditions) {
            if (!matchesCondition(condition, context)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(TrafficConfig.RuleCondition condition, Map<String, Object> context) {
        Object actualValue = context.get(condition.getField());
        return switch (condition.getOperator()) {
            case EQ -> actualValue != null && actualValue.toString().equalsIgnoreCase(condition.getValue());
            case IN -> actualValue != null && condition.getValues().stream()
                    .filter(StringUtils::hasText)
                    .anyMatch(value -> value.equalsIgnoreCase(actualValue.toString()));
            case CONTAINS -> actualValue != null && StringUtils.hasText(condition.getValue())
                    && actualValue.toString().contains(condition.getValue());
            case EXISTS -> actualValue != null && StringUtils.hasText(actualValue.toString());
        };
    }
}
