package com.pisces.service.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JSON转换工具
 */
@Component
@RequiredArgsConstructor
public class JsonUtil {

    private final ObjectMapper objectMapper;

    /**
     * 对象转JSON字符串
     */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON序列化失败", e);
        }
    }

    /**
     * JSON字符串转对象
     */
    public <T> T toObject(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("JSON反序列化失败", e);
        }
    }

    /**
     * JSON字符串转泛型对象
     */
    public <T> T toObject(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            throw new IllegalStateException("JSON反序列化失败", e);
        }
    }

    /**
     * 构建Redis专用ObjectMapper
     */
    public ObjectMapper buildRedisObjectMapper() {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        redisObjectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        return redisObjectMapper;
    }
}
