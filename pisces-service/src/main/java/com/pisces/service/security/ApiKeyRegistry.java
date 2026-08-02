package com.pisces.service.security;

import com.pisces.service.config.ApiKeyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API Key 注册表
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
@Slf4j
@Component
@AllArgsConstructor
public class ApiKeyRegistry {

    private static final String SPEC_FIELD_SEPARATOR = "\\|";
    private static final String SCOPE_SEPARATOR = "\\+";
    private static final String DEFAULT_APP_ID = "default";
    private static final String DEFAULT_OWNER = "system";

    private final ApiKeyProperties apiKeyProperties;

    public Optional<ApiKeyPrincipal> resolve(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return Optional.empty();
        }
        Optional<ApiKeyPrincipal> specPrincipal = resolveFromSpecs(apiKey);
        if (specPrincipal.isPresent()) {
            return specPrincipal;
        }
        return resolveLegacyKey(apiKey);
    }

    public List<ApiKeyPrincipal> listPrincipals() {
        List<ApiKeyPrincipal> principals = new ArrayList<>();
        apiKeyProperties.getApiKeySpecs().stream()
                .map(this::parseSpecPrincipal)
                .flatMap(Optional::stream)
                .forEach(principals::add);
        apiKeyProperties.getApiKeys().stream()
                .filter(StringUtils::hasText)
                .map(ignored -> legacyPrincipal())
                .forEach(principals::add);
        return List.copyOf(principals);
    }

    private Optional<ApiKeyPrincipal> resolveFromSpecs(String apiKey) {
        for (String apiKeySpec : apiKeyProperties.getApiKeySpecs()) {
            Optional<ApiKeyPrincipal> principal = parseSpec(apiKeySpec, apiKey);
            if (principal.isPresent()) {
                return principal;
            }
        }
        return Optional.empty();
    }

    private Optional<ApiKeyPrincipal> parseSpec(String apiKeySpec, String apiKey) {
        if (!StringUtils.hasText(apiKeySpec)) {
            return Optional.empty();
        }
        String[] fields = apiKeySpec.split(SPEC_FIELD_SEPARATOR, -1);
        String configuredKey = fields[0].trim();
        if (!StringUtils.hasText(configuredKey) || !configuredKey.equals(apiKey)) {
            return Optional.empty();
        }

        return Optional.of(principalFromFields(fields));
    }

    private Optional<ApiKeyPrincipal> parseSpecPrincipal(String apiKeySpec) {
        if (!StringUtils.hasText(apiKeySpec)) {
            return Optional.empty();
        }
        String[] fields = apiKeySpec.split(SPEC_FIELD_SEPARATOR, -1);
        String configuredKey = fields[0].trim();
        if (!StringUtils.hasText(configuredKey)) {
            return Optional.empty();
        }
        return Optional.of(principalFromFields(fields));
    }

    private Optional<ApiKeyPrincipal> resolveLegacyKey(String apiKey) {
        boolean matched = apiKeyProperties.getApiKeys().stream()
                .filter(StringUtils::hasText)
                .anyMatch(configuredKey -> configuredKey.equals(apiKey));
        if (!matched) {
            return Optional.empty();
        }

        return Optional.of(legacyPrincipal());
    }

    private ApiKeyPrincipal principalFromFields(String[] fields) {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(resolveField(fields, 1, DEFAULT_APP_ID));
        principal.setOwner(resolveField(fields, 2, DEFAULT_OWNER));
        principal.setScopes(parseScopes(resolveField(fields, 3, "")));
        return principal;
    }

    private ApiKeyPrincipal legacyPrincipal() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal();
        principal.setAppId(DEFAULT_APP_ID);
        principal.setOwner("legacy");
        principal.setScopes(EnumSet.allOf(ApiKeyScope.class));
        return principal;
    }

    private String resolveField(String[] fields, int index, String fallback) {
        if (fields.length <= index || !StringUtils.hasText(fields[index])) {
            return fallback;
        }
        return fields[index].trim();
    }

    private Set<ApiKeyScope> parseScopes(String scopeText) {
        if (!StringUtils.hasText(scopeText)) {
            return EnumSet.allOf(ApiKeyScope.class);
        }
        List<ApiKeyScope> scopes = Arrays.stream(scopeText.split(SCOPE_SEPARATOR))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::parseScope)
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
        if (scopes.isEmpty()) {
            return Collections.emptySet();
        }
        return EnumSet.copyOf(scopes);
    }

    private Optional<ApiKeyScope> parseScope(String scopeText) {
        ApiKeyScope scope = ApiKeyScope.of(scopeText);
        if (scope == null) {
            log.warn("忽略未知 API Key 权限域: {}", scopeText);
            return Optional.empty();
        }
        return Optional.of(scope);
    }
}
