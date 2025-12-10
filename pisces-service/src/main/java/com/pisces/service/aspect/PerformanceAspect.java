package com.pisces.service.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * 性能监控切面
 */
@Slf4j
@Aspect
@Component
public class PerformanceAspect {
    
    /**
     * 性能阈值（毫秒）
     */
    private static final long PERFORMANCE_THRESHOLD = 1000;
    
    /**
     * 切点：所有service实现类的方法
     */
    @Pointcut("execution(* com.pisces.service.impl.*.*(..))")
    public void serviceMethods() {
    }
    
    /**
     * 环绕通知：监控方法执行性能
     */
    @Around("serviceMethods()")
    public Object monitorPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long executionTime = System.currentTimeMillis() - startTime;
            
            if (executionTime > PERFORMANCE_THRESHOLD) {
                String methodName = joinPoint.getSignature().getName();
                String className = joinPoint.getTarget().getClass().getSimpleName();
                log.warn("性能警告: {}.{} 执行耗时 {}ms，超过阈值 {}ms", 
                        className, methodName, executionTime, PERFORMANCE_THRESHOLD);
            }
            
            return result;
        } catch (Throwable e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String methodName = joinPoint.getSignature().getName();
            String className = joinPoint.getTarget().getClass().getSimpleName();
            log.error("方法执行失败: {}.{}, 耗时: {}ms", className, methodName, executionTime);
            throw e;
        }
    }
}

