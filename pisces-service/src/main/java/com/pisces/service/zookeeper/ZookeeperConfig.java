package com.pisces.service.zookeeper;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Zookeeper配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "zookeeper")
public class ZookeeperConfig {
    
    /**
     * Zookeeper连接地址
     */
    private String connectString = "localhost:2181";
    
    /**
     * 会话超时时间（毫秒）
     */
    private int sessionTimeoutMs = 30000;
    
    /**
     * 连接超时时间（毫秒）
     */
    private int connectionTimeoutMs = 30000;
    
    /**
     * 重试次数
     */
    private int maxRetries = 3;
    
    /**
     * 基础路径
     */
    private String basePath = "/pisces";
}

