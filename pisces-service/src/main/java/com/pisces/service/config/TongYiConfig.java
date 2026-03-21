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
     * 文生图模型名称
     */
    private String imageGenerationModel = "wan2.6-t2i";

    /**
     * 图像编辑模型名称
     */
    private String imageEditModel = "wan2.6-image";
    
    /**
     * API请求超时时间（毫秒，默认：30000）
     */
    private int timeout = 30000;
    
    /**
     * 是否启用通义API（默认：true）
     * 如果为false，将直接拒绝AI请求
     */
    private boolean enabled = true;
}
