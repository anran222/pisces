package com.pisces.service.security;

import com.pisces.service.config.ApiKeyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 注册表测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
class ApiKeyRegistryTest {

    @Test
    void resolveShouldReturnAppOwnerAndScopesFromSpec() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeySpecs(List.of("runtime-key|shop-app|sdk-owner|runtime+analysis"));
        ApiKeyRegistry registry = new ApiKeyRegistry(properties);

        Optional<ApiKeyPrincipal> principal = registry.resolve("runtime-key");

        assertThat(principal).isPresent();
        assertThat(principal.get().getAppId()).isEqualTo("shop-app");
        assertThat(principal.get().getOwner()).isEqualTo("sdk-owner");
        assertThat(principal.get().getScopes()).containsExactlyInAnyOrder(ApiKeyScope.RUNTIME, ApiKeyScope.ANALYSIS);
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.RUNTIME))).isTrue();
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.MANAGEMENT))).isFalse();
    }

    @Test
    void resolveShouldKeepLegacyApiKeysWithAllScopes() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of("legacy-key"));
        ApiKeyRegistry registry = new ApiKeyRegistry(properties);

        Optional<ApiKeyPrincipal> principal = registry.resolve("legacy-key");

        assertThat(principal).isPresent();
        assertThat(principal.get().getAppId()).isEqualTo("default");
        assertThat(principal.get().getOwner()).isEqualTo("legacy");
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.MANAGEMENT))).isTrue();
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.RUNTIME))).isTrue();
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.ANALYSIS))).isTrue();
    }

    @Test
    void resolveShouldNotGrantAccessWhenExplicitScopesAreInvalid() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeySpecs(List.of("bad-scope-key|shop-app|owner|unknown"));
        ApiKeyRegistry registry = new ApiKeyRegistry(properties);

        Optional<ApiKeyPrincipal> principal = registry.resolve("bad-scope-key");

        assertThat(principal).isPresent();
        assertThat(principal.get().getScopes()).isEmpty();
        assertThat(principal.get().hasAnyScope(List.of(ApiKeyScope.RUNTIME))).isFalse();
    }
}
