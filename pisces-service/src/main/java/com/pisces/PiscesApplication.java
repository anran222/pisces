package com.pisces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 主应用类（无用户系统版本）
 */
@SpringBootApplication(scanBasePackages = "com.pisces")
public class PiscesApplication {

    public static void main(String[] args) {
        SpringApplication.run(PiscesApplication.class, args);
    }
}


