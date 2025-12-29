package com.example.springboot4demo.dto;

/**
 * 상품 응답 DTO (API v1.x)
 *
 * @deprecated v2.0 사용 권장
 * @see ProductV2Response
 */
@Deprecated(since = "2.0", forRemoval = true)
public record ProductV1Response(
        Long id,
        String name,
        Integer price,
        String category
) {
    // v1.0 호환성을 위한 팩토리 메서드
    public static ProductV1Response withoutCategory(Long id, String name, Integer price) {
        return new ProductV1Response(id, name, price, null);
    }
}
