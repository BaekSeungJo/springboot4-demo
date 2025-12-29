package com.example.springboot4demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * API 버전 관리 설정
 *
 * Spring Framework 7.x의 새로운 API Versioning 기능 활용
 * RFC 9745 (Deprecation) + RFC 8594 (Sunset) 표준 준수
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {

        // ====================================================
        // 1. 버전 추출 방식 설정
        // ====================================================
        configurer
                // 헤더 기반 버전 추출
                .useRequestHeader("X-API-Version");

        // ====================================================
        // 2. 버전 필수 여부 및 기본값
        // ====================================================

        // 기본 버전 설정 (버전 미지정 시 사용)
        configurer.setDefaultVersion("2.0");

        // 버전 필수 여부 (defatulVersion 설정 시 자동으로 false)
        // configurer.setVersionRequired(false);

        // ====================================================
        // 3. 지원 버전 명시 (선택사항)
        // ====================================================
        configurer
                // 명시적으로 지원 버전 추가
                .addSupportedVersions("1.0", "1.1", "2.0")
                // Controller 에서 자동 감지 활성화
                .detectSupportedVersions(true);

        // ====================================================
        // 4. Deprecation 처리 (핵심!)
        // ====================================================
        configurer
                .setDeprecationHandler(createDeprecationHandler());
    }

    /**
     * Deprecation 처리 핸들러 생성
     * RFC 9745 (Deprecation) + RFC 8594 (Sunset) 표준 준수
     */
    private StandardApiVersionDeprecationHandler createDeprecationHandler() {
        StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();

        // ====================================================
        // API v1.0 Deprecation 설정
        // ====================================================
        handler.configureVersion("1.0")
                // Deprecation 시작일 (이미 deprecated된 상태)
                .setDeprecationDate(LocalDate.of(2024, 1, 1).atStartOfDay(ZoneId.systemDefault()))
                // Sunset(완전 폐기) 예정일
                .setSunsetDate(LocalDate.of(2024, 7, 1).atStartOfDay(ZoneId.systemDefault()))
                .setDeprecationLink(URI.create("https://api.example.com/docs/v1-to-v2-migration"))
                .setSunsetLink(URI.create("https://api.example.com/docs/v1-sunset-notice"));

        // ====================================================
        // API v1.1 Deprecation 설정 (단계적 폐기)
        // ====================================================
        handler.configureVersion("1.1")
                .setDeprecationDate(LocalDate.of(2024, 6, 1).atStartOfDay(ZoneId.systemDefault()))
                .setSunsetDate(LocalDate.of(2024, 12, 31).atStartOfDay(ZoneId.systemDefault()))
                .setDeprecationLink(URI.create("https://api.example.com/docs/v1.1-migration"));

        return handler;
    }
}
