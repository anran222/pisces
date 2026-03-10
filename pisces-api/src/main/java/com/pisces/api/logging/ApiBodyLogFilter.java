package com.pisces.api.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一打印 API RequestBody / ResponseBody 的 Filter
 * - 使用 ContentCaching*Wrapper，不破坏正常读取流
 * - 自动截断、脱敏、跳过二进制/上传等内容
 * - 依赖 RequestIdFilter 注入 MDC(requestId)
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiBodyLogFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_CHARS = 8000;
    private static final int MAX_BODY_BYTES = 512 * 1024; // 512KB，超过则不打印

    // 简单 JSON 文本脱敏（尽量不误伤）
    private static final Pattern JSON_SENSITIVE_KV = Pattern.compile(
            "(?i)(\"(?:apiKey|apikey|api-key|token|access_token|refresh_token|password|secret)\"\\s*:\\s*\")([^\"]{0,2048})(\")"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 可按需排除：健康检查/静态资源等
        return uri == null || uri.startsWith("/actuator") || uri.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Instant start = Instant.now();

        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(reqWrapper, respWrapper);
        } finally {
            long costMs = Duration.between(start, Instant.now()).toMillis();

            try {
                logBodies(reqWrapper, respWrapper, costMs);
            } catch (Exception e) {
                // 日志失败不能影响主流程
                log.warn("[API][BODY] failed to log bodies: {}", e.getMessage());
            }

            // 必须调用，否则响应体会丢失
            respWrapper.copyBodyToResponse();
        }
    }

    private void logBodies(ContentCachingRequestWrapper req, ContentCachingResponseWrapper resp, long costMs) {
        String requestId = MDC.get("requestId");
        String method = req.getMethod();
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        int status = resp.getStatus();

        String reqContentType = safeLower(req.getContentType());
        String respContentType = safeLower(resp.getContentType());

        String reqBody = readRequestBody(req, reqContentType);
        String respBody = readResponseBody(resp, respContentType);

        log.info("[API][BODY] requestId={} {} {}{} status={} costMs={} reqContentType={} respContentType={} reqBody={} respBody={}",
                requestId,
                method,
                uri,
                (StringUtils.hasText(query) ? "?" + query : ""),
                status,
                costMs,
                req.getContentType(),
                resp.getContentType(),
                reqBody,
                respBody
        );
    }

    private String readRequestBody(ContentCachingRequestWrapper req, String contentTypeLower) {
        if (!isLoggableContentType(contentTypeLower)) {
            return "[skipped-content-type]";
        }
        byte[] buf = req.getContentAsByteArray();
        if (buf == null || buf.length == 0) return "";
        if (buf.length > MAX_BODY_BYTES) return "[skipped-too-large]";

        String body = bytesToString(buf, req.getCharacterEncoding(), contentTypeLower);
        return maskAndTruncate(body);
    }

    private String readResponseBody(ContentCachingResponseWrapper resp, String contentTypeLower) {
        if (!isLoggableContentType(contentTypeLower)) {
            return "[skipped-content-type]";
        }
        byte[] buf = resp.getContentAsByteArray();
        if (buf == null || buf.length == 0) return "";
        if (buf.length > MAX_BODY_BYTES) return "[skipped-too-large]";

        String body = bytesToString(buf, resp.getCharacterEncoding(), contentTypeLower);
        return maskAndTruncate(body);
    }

    private boolean isLoggableContentType(String contentTypeLower) {
        if (!StringUtils.hasText(contentTypeLower)) return true; // unknown -> log as text (often JSON)

        // 上传/二进制直接跳过
        if (contentTypeLower.startsWith("multipart/")) return false;
        if (contentTypeLower.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE)) return false;

        // 图片/音视频跳过
        if (contentTypeLower.startsWith("image/")) return false;
        if (contentTypeLower.startsWith("audio/")) return false;
        if (contentTypeLower.startsWith("video/")) return false;

        // 常见文本类型允许
        return contentTypeLower.contains("json")
                || contentTypeLower.contains("text")
                || contentTypeLower.contains("xml")
                || contentTypeLower.contains("x-www-form-urlencoded")
                || contentTypeLower.contains("javascript");
    }

    private String bytesToString(byte[] bytes, String encoding, String contentTypeLower) {
        Charset charset = StandardCharsets.UTF_8;
        if (StringUtils.hasText(encoding)) {
            try {
                charset = Charset.forName(encoding);
            } catch (Exception ignored) {
            }
        }
        if (shouldUseUtf8ByDefault(contentTypeLower, charset)) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(bytes, charset);
    }

    private boolean shouldUseUtf8ByDefault(String contentTypeLower, Charset charset) {
        if (!StringUtils.hasText(contentTypeLower)) {
            return StandardCharsets.ISO_8859_1.equals(charset);
        }
        boolean textualPayload = contentTypeLower.contains("json")
                || contentTypeLower.contains("text")
                || contentTypeLower.contains("xml")
                || contentTypeLower.contains("javascript")
                || contentTypeLower.contains("form-urlencoded");
        return textualPayload && StandardCharsets.ISO_8859_1.equals(charset);
    }

    private String maskAndTruncate(String body) {
        if (body == null) return null;
        String masked = JSON_SENSITIVE_KV.matcher(body).replaceAll("$1***$3");
        if (masked.length() <= MAX_BODY_CHARS) return masked;
        return masked.substring(0, MAX_BODY_CHARS) + "...(truncated)";
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
