package com.pisces;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 主应用类
 */
@SpringBootApplication(scanBasePackages = "com.pisces")
@MapperScan("com.pisces.service.mapper")
public class PiscesApplication {

    public static void main(String[] args) {
        SpringApplication.run(PiscesApplication.class, args);
    }
}


