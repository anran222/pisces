package com.pisces.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.annotation.ApiKeyScopeRequired;
import com.pisces.service.annotation.NoTokenRequired;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.security.ApiKeyPrincipal;
import com.pisces.service.security.ApiKeyRegistry;
import com.pisces.service.security.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API Key 鉴权拦截器
 *
 * <p>规则：
 * <ol>
 *   <li>路径命中 {@code pisces.security.skip-paths} 时直接放行。</li>
 *   <li>控制器方法或其类上标注了 {@link NoTokenRequired} 时直接放行。</li>
 *   <li>其他请求必须在 Header {@code X-Pisces-Api-Key} 携带有效 Key，否则返回 401。</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-Pisces-Api-Key";

    private static final List<ApiKeyScope> DEFAULT_REQUIRED_SCOPES = List.of(ApiKeyScope.MANAGEMENT);

    private final ApiKeyProperties apiKeyProperties;

    private final ApiKeyRegistry apiKeyRegistry;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        ApiKeyContextHolder.clear();
        String path = request.getServletPath();

        // 1. skip-paths 放行
        for (String skipPath : apiKeyProperties.getSkipPaths()) {
            if (path.startsWith(skipPath)) {
                return true;
            }
        }

        // 2. 非 HandlerMethod（静态资源等）放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        List<ApiKeyScope> requiredScopes = resolveRequiredScopes(handlerMethod);

        // 3. 没有显式权限域的旧免鉴权接口保持兼容
        if (requiredScopes.isEmpty() && isNoTokenRequired(handlerMethod)) {
            return true;
        }

        // 4. 校验 API Key 归属和权限域
        String apiKey = request.getHeader(API_KEY_HEADER);
        Optional<ApiKeyPrincipal> principal = apiKeyRegistry.resolve(apiKey);
        if (principal.isEmpty()) {
            log.warn("未授权请求: method={} path={} ip={}", request.getMethod(), path, request.getRemoteAddr());
            sendUnauthorized(response);
            return false;
        }

        List<ApiKeyScope> effectiveRequiredScopes =
                requiredScopes.isEmpty() ? DEFAULT_REQUIRED_SCOPES : requiredScopes;
        ApiKeyPrincipal apiKeyPrincipal = principal.get();
        if (!apiKeyPrincipal.hasAnyScope(effectiveRequiredScopes)) {
            log.warn("API Key 权限不足: method={} path={} appId={} owner={} requiredScopes={}",
                    request.getMethod(), path, apiKeyPrincipal.getAppId(), apiKeyPrincipal.getOwner(),
                    effectiveRequiredScopes);
            sendForbidden(response);
            return false;
        }

        request.setAttribute(ApiKeyPrincipal.REQUEST_ATTRIBUTE, apiKeyPrincipal);
        ApiKeyContextHolder.set(apiKeyPrincipal);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ApiKeyContextHolder.clear();
    }

    private List<ApiKeyScope> resolveRequiredScopes(HandlerMethod handlerMethod) {
        ApiKeyScopeRequired methodAnnotation = handlerMethod.getMethodAnnotation(ApiKeyScopeRequired.class);
        if (methodAnnotation != null) {
            return Arrays.asList(methodAnnotation.value());
        }
        ApiKeyScopeRequired classAnnotation = handlerMethod.getBeanType().getAnnotation(ApiKeyScopeRequired.class);
        if (classAnnotation != null) {
            return Arrays.asList(classAnnotation.value());
        }
        return List.of();
    }

    private boolean isNoTokenRequired(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(NoTokenRequired.class)
                || handlerMethod.getBeanType().isAnnotationPresent(NoTokenRequired.class);
    }

    private void sendUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "code", ResponseCode.UNAUTHORIZED.getCode(),
                "message", "未授权：请在请求头 X-Pisces-Api-Key 中携带有效的 API Key"
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void sendForbidden(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "code", ResponseCode.FORBIDDEN.getCode(),
                "message", "禁止访问：当前 API Key 权限不足"
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
