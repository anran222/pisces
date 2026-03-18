package com.pisces.service.service.impl;

import com.pisces.service.service.HTEAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异质处理效应（HTE）分析服务实现
 * 通过因果森林、元学习等方法，识别对策略敏感的用户群体，实现个性化策略落地
 */
@Slf4j
@Service
public class HTEAnalysisServiceImpl implements HTEAnalysisService {

    private static final String STATUS_BLOCKED = "BLOCKED";

    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private static final List<String> SUPPORTED_COVARIATES = Arrays.asList(
            "viewCount", "clickCount", "eventCount", "rank");

    @Override
    public Map<String, Object> analyzeHTE(String experimentId, String treatmentGroupId,
                                          String controlGroupId, List<String> userFeatures) {
        log.info("执行HTE分析: experimentId={}, treatment={}, control={}, features={}",
                experimentId, treatmentGroupId, controlGroupId, userFeatures);
        List<String> requestedCovariates = sanitizeFeatures(userFeatures);
        if (requestedCovariates.isEmpty()) {
            return buildBlockedResult("HTE", STATUS_BLOCKED,
                    "HTE 分析需要显式协变量输入",
                    Collections.singletonList("userFeatures 不能为空"),
                    buildContract(requestedCovariates, Collections.singletonList("userFeatures"), SUPPORTED_COVARIATES));
        }
        List<String> unsupportedCovariates = requestedCovariates.stream()
                .filter(covariate -> !SUPPORTED_COVARIATES.contains(covariate))
                .collect(java.util.stream.Collectors.toList());
        if (!unsupportedCovariates.isEmpty()) {
            return buildBlockedResult("HTE", STATUS_BLOCKED,
                    "HTE 当前只接受事件级代理协变量",
                    unsupportedCovariates.stream()
                            .map(value -> "不支持的协变量: " + value)
                            .collect(java.util.stream.Collectors.toList()),
                    buildContract(requestedCovariates, Collections.singletonList("userFeatures"), SUPPORTED_COVARIATES));
        }
        return buildUnavailableResult("HTE",
                "HTE分析尚未接入真实模型，当前仅保留接口契约",
                requestedCovariates,
                Collections.singletonList("未接入真实因果模型"),
                Collections.singletonList("userFeatures"));
    }

    @Override
    public Map<String, Object> getIndividualTreatmentEffect(String experimentId, String visitorId,
                                                            String treatmentGroupId, String controlGroupId) {
        log.debug("获取个体处理效应: experimentId={}, visitorId={}", experimentId, visitorId);
        if (!StringUtils.hasText(visitorId)) {
            return buildBlockedResult("ITE", STATUS_BLOCKED,
                    "个体处理效应需要 visitorId",
                    Collections.singletonList("visitorId 不能为空"),
                    buildVisitorContract(visitorId));
        }
        return buildUnavailableVisitorResult(visitorId,
                "个体处理效应尚未接入真实模型，当前仅保留接口契约",
                Collections.singletonList("未接入真实因果模型"));
    }

    @Override
    public Map<String, Object> identifySensitiveGroups(String experimentId, String treatmentGroupId,
                                                        String controlGroupId, List<String> userFeatures) {
        log.info("识别敏感群体: experimentId={}, treatment={}, control={}",
                experimentId, treatmentGroupId, controlGroupId);
        List<String> requestedCovariates = sanitizeFeatures(userFeatures);
        if (requestedCovariates.isEmpty()) {
            return buildBlockedResult("IDENTIFY_SENSITIVE_GROUPS", STATUS_BLOCKED,
                    "敏感群体识别需要显式协变量输入",
                    Collections.singletonList("userFeatures 不能为空"),
                    buildContract(requestedCovariates, Collections.singletonList("userFeatures"), SUPPORTED_COVARIATES));
        }
        List<String> unsupportedCovariates = requestedCovariates.stream()
                .filter(covariate -> !SUPPORTED_COVARIATES.contains(covariate))
                .collect(java.util.stream.Collectors.toList());
        if (!unsupportedCovariates.isEmpty()) {
            return buildBlockedResult("IDENTIFY_SENSITIVE_GROUPS", STATUS_BLOCKED,
                    "敏感群体识别当前只接受事件级代理协变量",
                    unsupportedCovariates.stream()
                            .map(value -> "不支持的协变量: " + value)
                            .collect(java.util.stream.Collectors.toList()),
                    buildContract(requestedCovariates, Collections.singletonList("userFeatures"), SUPPORTED_COVARIATES));
        }
        return buildUnavailableResult("IDENTIFY_SENSITIVE_GROUPS",
                "敏感群体识别尚未接入真实模型，当前仅保留接口契约",
                requestedCovariates,
                Collections.singletonList("未接入真实因果模型"),
                Collections.singletonList("userFeatures"));
    }

    private Map<String, Object> buildUnavailableResult(String method, String reason,
                                                      List<String> requestedCovariates,
                                                      List<String> blockingIssues,
                                                      List<String> requiredInputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method);
        result.put("status", STATUS_UNAVAILABLE);
        result.put("blocked", true);
        result.put("analysisReady", false);
        result.put("reason", reason);
        result.put("blockingIssues", blockingIssues);
        result.put("warnings", Collections.emptyList());
        result.put("inputContract", buildContract(requestedCovariates, requiredInputs, SUPPORTED_COVARIATES));
        return result;
    }

    private Map<String, Object> buildUnavailableVisitorResult(String visitorId, String reason,
                                                              List<String> blockingIssues) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "ITE");
        result.put("status", STATUS_UNAVAILABLE);
        result.put("blocked", true);
        result.put("analysisReady", false);
        result.put("reason", reason);
        result.put("blockingIssues", blockingIssues);
        result.put("warnings", Collections.emptyList());
        result.put("inputContract", buildVisitorContract(visitorId));
        return result;
    }

    private Map<String, Object> buildBlockedResult(String method, String status, String reason,
                                                   List<String> blockingIssues, Map<String, Object> contract) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", method);
        result.put("status", status);
        result.put("blocked", true);
        result.put("analysisReady", false);
        result.put("reason", reason);
        result.put("blockingIssues", blockingIssues);
        result.put("warnings", Collections.emptyList());
        result.put("inputContract", contract);
        return result;
    }

    private Map<String, Object> buildContract(List<String> requestedCovariates, List<String> requiredInputs,
                                              List<String> supportedCovariates) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requiredInputs", requiredInputs);
        contract.put("requestedCovariates", requestedCovariates);
        contract.put("supportedCovariates", supportedCovariates);
        contract.put("available", false);
        return contract;
    }

    private Map<String, Object> buildVisitorContract(String visitorId) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requiredInputs", Collections.singletonList("visitorId"));
        Map<String, Object> providedInputs = new LinkedHashMap<>();
        providedInputs.put("visitorId", visitorId);
        contract.put("providedInputs", providedInputs);
        contract.put("available", false);
        return contract;
    }

    private List<String> sanitizeFeatures(List<String> userFeatures) {
        if (userFeatures == null || userFeatures.isEmpty()) {
            return Collections.emptyList();
        }
        return userFeatures.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }
}
