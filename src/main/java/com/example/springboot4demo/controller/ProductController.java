package com.example.springboot4demo.controller;

import com.example.springboot4demo.dto.CreateProductRequest;
import com.example.springboot4demo.dto.ProductListV2Response;
import com.example.springboot4demo.dto.ProductV1Response;
import com.example.springboot4demo.dto.ProductV2Response;
import com.example.springboot4demo.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // ===========================================================================
    // API v1.0 - Deprecated (2025-07-01 폐기 예정)
    // ===========================================================================

    /**
     * 상품 목록 조회 (v1.0)
     *
     * @see #getProductsV2
     * @deprecated V2.0 사용 권장. 2024-07-01 폐기 예정
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(version = "1.0")
    public ResponseEntity<List<ProductV1Response>> getProductsV1() {
        // v1에서는 단순히 필드만 제공
        List<ProductV1Response> products = productService.getProductsV1();
        return ResponseEntity.ok(products);
    }

    /**
     * 상품 상세 조회 (v1.0)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(value = "/{id}", version = "1.0")
    public ResponseEntity<ProductV1Response> getProductV1(@PathVariable Long id) {
        ProductV1Response product = productService.getProductByIdV1(id);
        return ResponseEntity.ok(product);
    }

    // ===========================================================================
    // API v1.1 - Deprecated (2025-12-31 폐기 예정)
    // ===========================================================================

    /**
     * 상품 목록 조회 (v1.1)
     * <p>
     * v1.0 대기 개선사항: 카테고리 필드 추가
     *
     * @see #getProductsV2
     * @deprecated v2.0 사용 권장. 2024-12-31 폐기 예정.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(value = "1.1")
    public ResponseEntity<List<ProductV1Response>> getProductsV1_1() {
        // v1.1에서는 카테고리 포함
        List<ProductV1Response> products = productService.getProductsV1WithCategory();
        return ResponseEntity.ok(products);
    }

    // ===========================================================================
    // API v2.0 - Current (권장 버전)
    // ===========================================================================

    /**
     * 상품 목록 조회 (v2.0) - 권장
     * <p>
     * 개선사항:
     * - 페이지네이션 지원
     * - 풍부한 메타데이터 제공
     * - HATEOAS 링크 포함
     */
    @GetMapping(version = "2.0")
    public ResponseEntity<ProductListV2Response> getProductsV2(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ProductListV2Response response = productService.getProductsV2(page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 상품 상세 조회 (v2.0)
     */
    @GetMapping(value = "/{id}", version = "2.0")
    public ResponseEntity<ProductV2Response> getProductV2(@PathVariable Long id) {
        ProductV2Response product = productService.getProductByIdV2(id);
        return ResponseEntity.ok(product);
    }

    /**
     * 상품 생성 (v2.0)
     * <p>
     * v1에서는 지원하지 않던기능
     */
    @PostMapping(version = "2.0")
    public ResponseEntity<ProductV2Response> createProduct(@RequestBody CreateProductRequest request) {
        ProductV2Response product = productService.createProduct(request);
        return ResponseEntity
                .created(
                        ServletUriComponentsBuilder
                                .fromCurrentRequest()
                                .path("/{id}")
                                .buildAndExpand(product.id())
                                .toUri()
                )
                .body(product);
    }
}
