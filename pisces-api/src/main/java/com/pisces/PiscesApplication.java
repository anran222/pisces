package com.pisces;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Pisces API application entrypoint.
 */
@SpringBootApplication(scanBasePackages = "com.pisces")
@MapperScan("com.pisces.service.mapper")
@EnableScheduling
public class PiscesApplication {

    public static void main(String[] args) {
        SpringApplication.run(PiscesApplication.class, args);
    }
}
