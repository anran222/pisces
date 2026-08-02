package com.pisces.service.annotation;

import com.pisces.service.security.ApiKeyScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API Key 权限域要求
 *
 * @author anran.xiang@atrenew.com
 * @date 2026/7/1 11:07
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiKeyScopeRequired {

    ApiKeyScope[] value();
}
