package com.pisces.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pisces.service.annotation.NoTokenRequired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

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
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-Pisces-Api-Key";

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

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

        // 3. @NoTokenRequired 放行
        if (handlerMethod.hasMethodAnnotation(NoTokenRequired.class)
                || handlerMethod.getBeanType().isAnnotationPresent(NoTokenRequired.class)) {
            return true;
        }

        // 4. 校验 API Key
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && apiKeyProperties.getApiKeys().contains(apiKey)) {
            return true;
        }

        log.warn("未授权请求: method={} path={} ip={}", request.getMethod(), path, request.getRemoteAddr());
        sendUnauthorized(response);
        return false;
    }

    private void sendUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = Map.of(
                "code", 401,
                "message", "未授权：请在请求头 X-Pisces-Api-Key 中携带有效的 API Key"
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
