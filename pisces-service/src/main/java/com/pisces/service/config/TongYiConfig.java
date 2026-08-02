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

    public static final String LATEST_TEXT_MODEL = "qwen3.7-max";

    public static final String STABLE_TEXT_MODEL = "qwen3.7-max";

    public static final String PREVIEW_TEXT_MODEL = "qwen3.8-max-preview";

    public static final String OPENAI_COMPATIBLE_API_MODE = "openai-compatible";

    public static final String DASHSCOPE_API_MODE = "dashscope";

    public static final String TOKEN_PLAN_COMPATIBLE_BASE_URL =
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    public static final String DASHSCOPE_HTTP_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    
    /**
     * API Key（从环境变量或配置文件中读取）
     */
    private String apiKey;
    
    /**
     * 文本生成模型名称。
     * 默认使用普通 DashScope API Key 可直接调用的 Qwen Max 生产模型。
     */
    private String model = LATEST_TEXT_MODEL;

    /**
     * 文本模型主调用协议。
     */
    private String apiMode = DASHSCOPE_API_MODE;

    /**
     * 文本模型主调用地址。
     */
    private String baseUrl = DASHSCOPE_HTTP_BASE_URL;

    /**
     * 主模型不可用时使用的稳定文本模型。
     */
    private String fallbackModel = STABLE_TEXT_MODEL;

    /**
     * 稳定模型回退调用协议。
     */
    private String fallbackApiMode = DASHSCOPE_API_MODE;

    /**
     * 稳定模型回退调用地址。
     */
    private String fallbackBaseUrl = DASHSCOPE_HTTP_BASE_URL;

    /**
     * 是否允许从最新模型自动退到稳定模型。
     */
    private boolean modelFallbackEnabled = true;

    /**
     * OpenAI-compatible 调用时是否开启思考模式。
     */
    private boolean enableThinking = true;

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
