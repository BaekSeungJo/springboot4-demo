package com.example.springboot4demo.dto;

import java.util.List;

/**
 * 상품 목록 응답 (v2.0 - 페이지네이션 포함)
 */
public record ProductListV2Response(
        List<ProductV2Response> content,
        PageInfo page
) {
    public record PageInfo(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {}
}
