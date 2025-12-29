package com.example.springboot4demo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 상품 응답 DTO (API v2.0)
 * <p>
 * v1 대시 개선사항:
 * - BigDecimal 가격 (정밀도 향상)
 * - 다국어 지원 (translations)
 * - 메타데이터 확장
 * - HATEOAS 링크
 */
public record ProductV2Response(
        Long id,
        String name,
        BigDecimal price,                           // 정밀한 가격 표현
        String currency,                            // 통화 정보 추가
        CategoryInfo category,                      // 카토게리 상세 정보
        Map<String, String> translations,           // 다국어 지원
        ProductMetadata metadata,                   // 확장된 메타데이터
        List<Link> links                            // HATEOAS 링크
) {
    public record CategoryInfo(
            Long id,
            String name,
            String path
    ) {
    }

    public record ProductMetadata(
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            Integer viewCount
    ) {
    }

    public record Link(
            String rel,
            String href,
            String method
    ) {
    }
}
