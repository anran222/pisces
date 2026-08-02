package com.pisces.service.security;

import lombok.Data;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * API Key 身份信息
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
@Data
public class ApiKeyPrincipal {

    public static final String REQUEST_ATTRIBUTE = "piscesApiKeyPrincipal";

    private String appId;

    private String owner;

    private Set<ApiKeyScope> scopes = Collections.emptySet();

    public boolean hasAnyScope(Collection<ApiKeyScope> requiredScopes) {
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            return true;
        }
        if (scopes.contains(ApiKeyScope.ADMIN)) {
            return true;
        }
        EnumSet<ApiKeyScope> requiredScopeSet = EnumSet.copyOf(requiredScopes);
        requiredScopeSet.retainAll(scopes);
        return !requiredScopeSet.isEmpty();
    }
}
