package com.pisces.api.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求注入 requestId（支持从请求头透传），便于串联排查。
 * - 入参头：X-Request-Id（可选）
 * - 出参头：X-Request-Id（必返回）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String rid = request.getHeader(HEADER);
        if (!StringUtils.hasText(rid)) {
            rid = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put("requestId", rid);
        response.setHeader(HEADER, rid);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}

