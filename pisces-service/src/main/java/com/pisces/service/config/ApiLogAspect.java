package com.pisces.service.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * API层统一请求/响应日志切面
 * - 打印：method、path、query、headers(脱敏)、args(脱敏)、response摘要、耗时、异常
 * - 适用于排查线上接口问题（建议配合requestId）
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class ApiLogAspect {

    private static final int MAX_TEXT_LEN = 2000;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "cookie", "set-cookie",
            "apikey", "api-key", "token", "access_token", "refresh_token", "password", "secret"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object aroundController(ProceedingJoinPoint pjp) throws Throwable {
        Instant start = Instant.now();

        HttpServletRequest req = currentRequest();
        String requestId = ensureRequestId();

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        String methodName = method.getDeclaringClass().getSimpleName() + "." + method.getName();

        Map<String, Object> reqLog = new LinkedHashMap<>();
        reqLog.put("requestId", requestId);
        reqLog.put("handler", methodName);

        if (req != null) {
            reqLog.put("httpMethod", req.getMethod());
            reqLog.put("path", req.getRequestURI());
            reqLog.put("query", req.getQueryString());
            reqLog.put("remote", req.getRemoteAddr());
            reqLog.put("ua", safeTruncate(req.getHeader("User-Agent")));
            reqLog.put("headers", maskedHeaders(req));
        }

        reqLog.put("args", maskedArgs(pjp.getArgs(), sig.getParameterNames()));

        log.info("[API][REQ] {}", toJsonSafely(reqLog));

        try {
            Object ret = pjp.proceed();
            long costMs = Duration.between(start, Instant.now()).toMillis();

            Map<String, Object> respLog = new LinkedHashMap<>();
            respLog.put("requestId", requestId);
            respLog.put("handler", methodName);
            respLog.put("costMs", costMs);
            respLog.put("result", summarizeResult(ret));

            log.info("[API][RESP] {}", toJsonSafely(respLog));
            return ret;
        } catch (Throwable t) {
            long costMs = Duration.between(start, Instant.now()).toMillis();
            Map<String, Object> errLog = new LinkedHashMap<>();
            errLog.put("requestId", requestId);
            errLog.put("handler", methodName);
            errLog.put("costMs", costMs);
            errLog.put("errorType", t.getClass().getName());
            errLog.put("message", safeTruncate(t.getMessage()));
            log.error("[API][ERR] {}", toJsonSafely(errLog), t);
            throw t;
        }
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private String ensureRequestId() {
        String existing = MDC.get("requestId");
        if (StringUtils.hasText(existing)) return existing;
        String rid = UUID.randomUUID().toString().replace("-", "");
        MDC.put("requestId", rid);
        return rid;
    }

    private Map<String, String> maskedHeaders(HttpServletRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        if (req == null) return map;
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            String value = req.getHeader(name);
            if (!StringUtils.hasText(value)) continue;
            if (isSensitiveKey(lower)) {
                map.put(name, maskValue(value));
            } else {
                map.put(name, safeTruncate(value));
            }
        }
        // 常用头补充（避免丢失）
        if (!map.containsKey(HttpHeaders.CONTENT_TYPE) && StringUtils.hasText(req.getContentType())) {
            map.put(HttpHeaders.CONTENT_TYPE, req.getContentType());
        }
        return map;
    }

    private Object maskedArgs(Object[] args, String[] paramNames) {
        List<Object> out = new ArrayList<>();
        if (args == null || args.length == 0) {
            return out;
        }
        for (int i = 0; i < args.length; i++) {
            String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : ("arg" + i);
            Object v = args[i];
            // 跳过Servlet相关对象，避免噪声与序列化问题
            if (v instanceof HttpServletRequest) continue;
            String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (isSensitiveKey(key)) {
                out.add(singleEntry(name, "***"));
            } else {
                out.add(singleEntry(name, summarizeValue(v)));
            }
        }
        return out;
    }

    private Object summarizeResult(Object ret) {
        if (ret == null) return null;
        // BaseResponse 等对象直接做摘要
        return summarizeValue(ret);
    }

    private Object summarizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return safeTruncate(s);
        if (v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof Map<?, ?> m) return summarizeMap(m);
        if (v instanceof Collection<?> c) return Map.of("type", "list", "size", c.size(), "preview", previewCollection(c));
        // 兜底：JSON序列化后截断
        return safeTruncate(toJsonSafely(v));
    }

    private Map<String, Object> summarizeMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "map");
        out.put("size", m.size());
        // 只抽取前20个key，避免过大
        int i = 0;
        Map<String, Object> preview = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (i++ >= 20) break;
            String k = String.valueOf(e.getKey());
            String lower = k.toLowerCase(Locale.ROOT);
            Object val = e.getValue();
            if (isSensitiveKey(lower)) {
                preview.put(k, "***");
            } else {
                preview.put(k, summarizeValue(val));
            }
        }
        out.put("preview", preview);
        return out;
    }

    private List<Object> previewCollection(Collection<?> c) {
        List<Object> preview = new ArrayList<>();
        int i = 0;
        for (Object o : c) {
            if (i++ >= 10) break;
            preview.add(summarizeValue(o));
        }
        return preview;
    }

    private String toJsonSafely(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }

    private String safeTruncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_TEXT_LEN) return s;
        return s.substring(0, MAX_TEXT_LEN) + "...(truncated)";
    }

    private String maskValue(String v) {
        if (!StringUtils.hasText(v)) return v;
        if (v.length() <= 8) return "****";
        return v.substring(0, 2) + "****" + v.substring(v.length() - 2);
    }

    private boolean isSensitiveKey(String key) {
        if (!StringUtils.hasText(key)) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(lower)
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("apikey")
                || lower.contains("api-key");
    }

    private Map<String, Object> singleEntry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(StringUtils.hasText(key) ? key : "unknown", value);
        return map;
    }
}
