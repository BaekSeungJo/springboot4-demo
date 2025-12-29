package com.example.springboot4demo.service;

import com.example.springboot4demo.dto.CreateProductRequest;
import com.example.springboot4demo.dto.ProductListV2Response;
import com.example.springboot4demo.dto.ProductV1Response;
import com.example.springboot4demo.dto.ProductV2Response;
import com.example.springboot4demo.exception.ProductNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 상품 서비스
 *
 * 버전별로 다른 응답 형식을 제공하는 예시
 */
@Service
public class ProductService {
    // 간단한 인메모리 저장소 (실제로는 Repository 사용)
    private final Map<Long, ProductEntity> products = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1L);

    public ProductService() {
        initSampleData();
    }

    private void initSampleData() {
        createProductInternal("노트북 Pro", new BigDecimal("1500000"), "KRW", 1L, "전자기기");
        createProductInternal("무선 마우스", new BigDecimal("45000"), "KRW", 1L, "전자기기");
        createProductInternal("기계식 키보드", new BigDecimal("120000"), "KRW", 1L, "전자기기");
        createProductInternal("모니터 27인치", new BigDecimal("350000"), "KRW", 1L, "전자기기");
        createProductInternal("USB-C 허브", new BigDecimal("55000"), "KRW", 2L, "액세서리");

    }

    // --------------------------------------------------------------------------------
    // API v1.0 용 메서드(레거시 형식)
    // --------------------------------------------------------------------------------

    /**
     * 상품 목록 조회 (v1.0)
     * - 카테고리 정보 없음
     * - 정수형 가격
     */
    public List<ProductV1Response> getProductsV1() {
        return products.values().stream()
                .map(entity -> ProductV1Response.withoutCategory(
                        entity.id(),
                        entity.name(),
                        entity.price().intValue()   // BigDecimal -> int 변환 (정밀도 손실)
                ))
                .toList();
    }

    /**
     * 상품 상세 조회 (v.1.0)
     */
    public ProductV1Response getProductByIdV1(Long id) {
        ProductEntity entity = findByIdOrThrow(id);
        return ProductV1Response.withoutCategory(
                entity.id(),
                entity.name(),
                entity.price().intValue()   // BigDecimal -> int 변환 (정밀도 손실)
        );
    }

    // --------------------------------------------------------------------------------
    // API v1.1 용 메서드(카테고리 추가)
    // --------------------------------------------------------------------------------

    /**
     * 상품 목록 조회 (v1.1)
     * - 카테고리 정보 포함
     */
    public List<ProductV1Response> getProductsV1WithCategory() {
        return products.values().stream()
                .map(entity -> new ProductV1Response(
                        entity.id(),
                        entity.name(),
                        entity.price().intValue(),
                        entity.categoryName()           // v1.1에서 카테고리 추가
                ))
                .toList();
    }

    // --------------------------------------------------------------------------------
    // API v2.0 용 메서드(확장된 형식)
    // --------------------------------------------------------------------------------

    /**
     * 상품 목록 조회 (v2.0)
     * - 페이지네이션 지원
     * - 풍부한 메타데이터
     */
    public ProductListV2Response getProductsV2(int page, int size) {
        List<ProductEntity> allProducts = products.values().stream().toList();

        // 간단한 페이지네이션 구현
        int start = page * size;
        int end = Math.min(start + size, allProducts.size());

        List<ProductV2Response> content = allProducts.stream()
                .skip(start)
                .limit(size)
                .map(this::toV2Response)
                .toList();

        int totalElements = allProducts.size();
        int totalPage = (int) Math.ceil((double) totalElements / size);

        return new ProductListV2Response(
                content,
                new ProductListV2Response.PageInfo(
                        page,
                        size,
                        totalElements,
                        totalPage,
                        page == 0,
                        page >= totalPage - 1
                )
        );
    }

    /**
     * 상품 상세 조회 (v2.0)
     */
    public ProductV2Response getProductByIdV2(Long id) {
        ProductEntity entity = findByIdOrThrow(id);
        return toV2Response(entity);
    }

    /**
     * 상품 생성 (v2.0)
     */
    public ProductV2Response createProduct(CreateProductRequest request) {
        ProductEntity entity = createProductInternal(
                request.name(),
                request.price(),
                request.currency(),
                request.categoryId(),
                request.categoryName()
        );
        return toV2Response(entity);
    }

    // --------------------------------------------------------------------------------
    // Private 헬퍼 메서드
    // --------------------------------------------------------------------------------
    private ProductEntity findByIdOrThrow(Long id) {
        ProductEntity entity = products.get(id);
        if(entity == null) {
            throw new ProductNotFoundException("Product not found: " + id);
        }
        return entity;
    }

    private ProductEntity createProductInternal(
            String name,
            BigDecimal price,
            String currenty,
            Long categoryId,
            String categoryName) {
        Long id = idGenerator.getAndIncrement();
        Instant now = Instant.now();

        ProductEntity entity = new ProductEntity(
                id,
                name,
                price,
                currenty,
                categoryId,
                categoryName,
                "/categories/" + categoryId + "/" + categoryName.toLowerCase(),
                now,
                now,
                "system",
                0
        );

        products.put(id, entity);
        return entity;
    }

    private ProductV2Response toV2Response(ProductEntity entity) {
        return new ProductV2Response(
                entity.id(),
                entity.name(),
                entity.price(),
                entity.currency(),
                new ProductV2Response.CategoryInfo(
                        entity.categoryId(),
                        entity.categoryName(),
                        entity.categoryPath()
                ),
                Map.of(
                        "ko", entity.name(),
                        "en", entity.name() + " (EN)"
                ),
                new ProductV2Response.ProductMetadata(
                        entity.createdAt(),
                        entity.updatedAt(),
                        entity.createdBy(),
                        entity.viewCount()
                ),
                List.of(
                        new ProductV2Response.Link("self", "/api/products/" + entity.id(), "GET"),
                        new ProductV2Response.Link("update", "/api/products/" + entity.id(), "PUT"),
                        new ProductV2Response.Link("delete", "/api/products/" + entity.id(), "DELETE"),
                        new ProductV2Response.Link("category", "/api/categories/" + entity.categoryId(), "GET")
                )
        );
    }


    // --------------------------------------------------------------------------------
    // 내부 엔티티 (실제로는 JPA Entity 사용 )
    // --------------------------------------------------------------------------------
    private record ProductEntity(
            Long id,
            String name,
            BigDecimal price,
            String currency,
            Long categoryId,
            String categoryName,
            String categoryPath,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            Integer viewCount
    ){}
}
