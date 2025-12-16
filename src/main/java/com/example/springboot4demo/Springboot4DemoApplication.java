package com.example.springboot4demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4.0 메인 애플리케이션
 *
 * 주요 변경사항:
 * - Spring Framework 7.0 기반
 * - Jakarta EE 11 지원
 * - 향상된 Null 안정성 (JSpecify)
 */
@SpringBootApplication
public class Springboot4DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(Springboot4DemoApplication.class, args);
    }
}
