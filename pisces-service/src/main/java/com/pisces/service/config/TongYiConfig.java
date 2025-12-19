package com.pisces.service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里通义千问API配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tongyi")
public class TongYiConfig {
    
    /**
     * API Key（从环境变量或配置文件中读取）
     */
    private String apiKey;
    
    /**
     * 模型名称（默认：qwen-plus）
     * 可选值：qwen-turbo, qwen-plus, qwen-max等
     */
    private String model = "qwen-plus";
    
    /**
     * API请求超时时间（毫秒，默认：30000）
     */
    private int timeout = 30000;
    
    /**
     * 是否启用通义API（默认：true）
     * 如果为false，将使用模拟数据
     */
    private boolean enabled = true;
}
