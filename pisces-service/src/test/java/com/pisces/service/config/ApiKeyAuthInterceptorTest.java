package com.pisces.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyRegistry;
import com.pisces.service.security.ApiKeyScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 拦截器测试
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
class ApiKeyAuthInterceptorTest {

    private static final String API_KEY_HEADER = "X-Pisces-Api-Key";
    private static final String MANAGEMENT_KEY = "management-key";
    private static final String RUNTIME_KEY = "runtime-key";

    @AfterEach
    void tearDown() {
        ApiKeyContextHolder.clear();
    }

    @Test
    void preHandleShouldAllowNoTokenEndpointWithoutScope() throws Exception {
        ApiKeyAuthInterceptor interceptor = newInterceptor();
        MockHttpServletRequest request = newRequest("/public");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("publicEndpoint"));

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(ApiKeyContextHolder.get()).isEmpty();
    }

    @Test
    void preHandleShouldRejectMissingKeyForScopedEndpoint() throws Exception {
        ApiKeyAuthInterceptor interceptor = newInterceptor();
        MockHttpServletRequest request = newRequest("/experiments");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("managementEndpoint"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void preHandleShouldRejectKeyWithoutRequiredScope() throws Exception {
        ApiKeyAuthInterceptor interceptor = newInterceptor();
        MockHttpServletRequest request = newRequest("/experiments");
        request.addHeader(API_KEY_HEADER, RUNTIME_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("managementEndpoint"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preHandleShouldRequireScopeEvenWhenEndpointHasNoTokenAnnotation() throws Exception {
        ApiKeyAuthInterceptor interceptor = newInterceptor();
        MockHttpServletRequest request = newRequest("/data/event");
        request.addHeader(API_KEY_HEADER, RUNTIME_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("runtimeEndpoint"));

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(ApiKeyPrincipal.REQUEST_ATTRIBUTE))
                .extracting("appId")
                .isEqualTo("runtime-app");
        assertThat(ApiKeyContextHolder.get())
                .get()
                .extracting(ApiKeyPrincipal::getAppId)
                .isEqualTo("runtime-app");

        interceptor.afterCompletion(request, response, handlerMethod("runtimeEndpoint"), null);

        assertThat(ApiKeyContextHolder.get()).isEmpty();
    }

    @Test
    void preHandleShouldAllowLegacyKeyForScopedEndpoint() throws Exception {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of("legacy-key"));
        ApiKeyAuthInterceptor interceptor = new ApiKeyAuthInterceptor(
                properties, new ApiKeyRegistry(properties), new ObjectMapper());
        MockHttpServletRequest request = newRequest("/experiments");
        request.addHeader(API_KEY_HEADER, "legacy-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod("managementEndpoint"));

        assertThat(allowed).isTrue();
    }

    private ApiKeyAuthInterceptor newInterceptor() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeySpecs(List.of(
                MANAGEMENT_KEY + "|management-app|ops|management+analysis",
                RUNTIME_KEY + "|runtime-app|sdk|runtime"
        ));
        return new ApiKeyAuthInterceptor(properties, new ApiKeyRegistry(properties), new ObjectMapper());
    }

    private MockHttpServletRequest newRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private HandlerMethod handlerMethod(String methodName) throws NoSuchMethodException {
        DummyController controller = new DummyController();
        Method method = DummyController.class.getDeclaredMethod(methodName);
        return new HandlerMethod(controller, method);
    }

    private static final class DummyController {

        @NoTokenRequired
        void publicEndpoint() {
        }

        @ApiKeyScopeRequired(ApiKeyScope.MANAGEMENT)
        void managementEndpoint() {
        }

        @NoTokenRequired
        @ApiKeyScopeRequired(ApiKeyScope.RUNTIME)
        void runtimeEndpoint() {
        }
    }
}
