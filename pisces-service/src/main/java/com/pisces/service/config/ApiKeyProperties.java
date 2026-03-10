package com.pisces.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Pisces 安全配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "pisces.security")
public class ApiKeyProperties {

    /**
     * 有效 API Key 列表
     */
    private List<String> apiKeys = List.of();

    /**
     * 完全跳过鉴权的路径前缀列表
     */
    private List<String> skipPaths = List.of("/actuator/");
}
