package com.pisces.service.security;

import com.pisces.common.enums.ResponseCode;
import com.pisces.common.model.Experiment;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.service.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * API Key 请求上下文
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:14
 */
public final class ApiKeyContextHolder {

    public static final String DEFAULT_APP_ID = "default";

    public static final String DEFAULT_OWNER = "system";

    private static final ThreadLocal<ApiKeyPrincipal> CURRENT = new ThreadLocal<>();

    private ApiKeyContextHolder() {
    }

    public static void set(ApiKeyPrincipal principal) {
        if (principal == null) {
            clear();
            return;
        }
        CURRENT.set(principal);
    }

    public static Optional<ApiKeyPrincipal> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String resolveCreateAppId(String requestedAppId) {
        ApiKeyPrincipal principal = CURRENT.get();
        if (principal == null || isAdmin(principal)) {
            return normalizeOrDefault(requestedAppId, principal != null ? principal.getAppId() : DEFAULT_APP_ID);
        }
        return normalizeOrDefault(principal.getAppId(), DEFAULT_APP_ID);
    }

    public static String resolveCreateOwner(String requestedOwner) {
        ApiKeyPrincipal principal = CURRENT.get();
        if (principal == null || isAdmin(principal)) {
            String principalOwner = principal != null && StringUtils.hasText(principal.getOwner())
                    ? principal.getOwner() : DEFAULT_OWNER;
            return normalizeOrDefault(requestedOwner, principalOwner);
        }
        return normalizeOrDefault(principal.getOwner(), DEFAULT_OWNER);
    }

    public static String resolveOperator(String requestedOperator) {
        ApiKeyPrincipal principal = CURRENT.get();
        if (principal != null && StringUtils.hasText(principal.getOwner())) {
            return principal.getOwner().trim();
        }
        return normalizeOrDefault(requestedOperator, DEFAULT_OWNER);
    }

    public static boolean canAccess(ExperimentMetadata metadata) {
        ApiKeyPrincipal principal = CURRENT.get();
        if (principal == null || isAdmin(principal)) {
            return true;
        }
        String principalAppId = normalizeOrDefault(principal.getAppId(), DEFAULT_APP_ID);
        String experimentAppId = resolveMetadataAppId(metadata);
        return principalAppId.equals(experimentAppId);
    }

    public static void assertCanAccess(ExperimentMetadata metadata) {
        if (!canAccess(metadata)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问当前应用实验");
        }
    }

    public static String resolveMetadataAppId(ExperimentMetadata metadata) {
        if (metadata == null) {
            return DEFAULT_APP_ID;
        }
        if (StringUtils.hasText(metadata.getAppId())) {
            return metadata.getAppId().trim();
        }
        Experiment experiment = metadata.getExperiment();
        if (experiment != null && StringUtils.hasText(experiment.getAppId())) {
            return experiment.getAppId().trim();
        }
        return DEFAULT_APP_ID;
    }

    public static String resolveMetadataOwner(ExperimentMetadata metadata) {
        if (metadata == null) {
            return DEFAULT_OWNER;
        }
        if (StringUtils.hasText(metadata.getOwner())) {
            return metadata.getOwner().trim();
        }
        Experiment experiment = metadata.getExperiment();
        if (experiment != null) {
            if (StringUtils.hasText(experiment.getOwner())) {
                return experiment.getOwner().trim();
            }
            if (StringUtils.hasText(experiment.getCreator())) {
                return experiment.getCreator().trim();
            }
        }
        return DEFAULT_OWNER;
    }

    public static boolean isAdmin(ApiKeyPrincipal principal) {
        return principal != null
                && principal.getScopes() != null
                && principal.getScopes().contains(ApiKeyScope.ADMIN);
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return StringUtils.hasText(defaultValue) ? defaultValue.trim() : DEFAULT_APP_ID;
    }
}
