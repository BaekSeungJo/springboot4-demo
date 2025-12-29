package com.example.springboot4demo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ApiVersionDeprecationTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Deprecated API v1.0 요청")
    class DeprecatedV1Tests {

        @Test
        @DisplayName("v1.0 요청 시 Deprecation 헤더가 포함되어야 한다")
        void shouldIncludeDeprecationHeader() throws Exception {
            mockMvc.perform(get("/api/products")
                            .header("X-API-Version", "1.0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    // Deprecation 헤더 검증 (RFC 9745)
                    .andExpect(header().exists("Deprecation"))
                    .andExpect(header().string("Deprecation",
                            matchesPattern("@\\d+"))) // Unix timestamp 형식
                    // Sunset 헤더 검증 (RFC 8594)
                    .andExpect(header().exists("Sunset"))
                    .andExpect(header().string("Sunset", containsString("2024")))
                    // Link 헤더 검증
                    .andExpect(header().string("Link", containsString("rel=\"deprecation\"")));
        }

        @Test
        @DisplayName("v1.0 요청 시 Link 헤더에 마이그레이션 가이드 URL이 포함되어야 한다.")
        void shouldIncludeMigrationLink() throws Exception {
            mockMvc.perform(get("/api/products")
                            .header("X-API-Version", "1.0"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Link", containsString("v1-to-v2-migration")));
        }

        @Test
        @DisplayName("v1.0 응답 본문은 레거시 형식이어야 한다.")
        void shouldReturnLegacyFormat() throws Exception {
            mockMvc.perform(get("/api/products/1")
                            .header("X-API-Version", "1.0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").isNumber()) // 정수형
                    .andExpect(jsonPath("$.currency").doesNotExist())  // 통화 없음
                    .andExpect(jsonPath("$.metadata").doesNotExist()); // 메타데이터 없음
        }
    }

    @Nested
    @DisplayName("Current API v2.0 요청")
    class CurrentV2Tests {

        @Test
        @DisplayName("v2.0 요청 시 Deprecation 헤더가 없어야 한다.")
        void shouldNotIncludeDeprecationHeader() throws Exception {
            mockMvc.perform(get("/api/products")
                            .header("X-API-Version", "2.0")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Deprecation"))
                    .andExpect(header().doesNotExist("Sunset"));
        }

        @Test
        @DisplayName("v2.0 응답 본문은 확장된 형식이어야 한다.")
        void shouldReturnExtendedFormat() throws Exception {
            mockMvc.perform(get("/api/products/1")
                            .header("X-API-Version", "2.0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.price").isNumber())
                    .andExpect(jsonPath("$.currency").exists())
                    .andExpect(jsonPath("$.metadata.createdAt").exists())
                    .andExpect(jsonPath("$.links").isArray());
        }
    }

    @Nested
    @DisplayName("버전 미지정 요청 (기본 버전 적용)")
    class DefaultVersionTests {

        @Test
        @DisplayName("버전 미지정 시 기본 버전(2.0)이 적용되어야 한다.")
        void shouldApplyDefaultVersion() throws Exception {
            mockMvc.perform(get("/api/products")
                            .accept(MediaType.APPLICATION_JSON))
                    // 버전 헤더 없이 요청
                    .andExpect(status().isOk())
                    // Deprecation 헤더 없음 (v2.0이 기본)
                    .andExpect(header().doesNotExist("Deprecation"))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.page").exists());
        }
    }

    @Nested
    @DisplayName("지원하지 않는 버전 요청")
    class UnsupportedVersionTests {

        @Test
        @DisplayName("v3.0 요청 시 400 Bad Request 응답")
        void shouldReject_UnsupportedVersion() throws Exception {
            mockMvc.perform(get("/api/products")
                            .header("X-API-Version", "3.0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("잘못된 버전 형식 요청 시 400 Bad Request 응답")
        void shouldReject_InvalidVersionFormat() throws Exception {
            mockMvc.perform(get("/api/products")
                            .header("X-API-Version", "invalid"))
                    .andExpect(status().isBadRequest());
        }
    }
}
