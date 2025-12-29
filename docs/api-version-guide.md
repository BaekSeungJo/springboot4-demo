# StandardApiVersionDeprecationHandler 완벽 가이드

## Spring Framework 7.x API 버전 Deprecation 처리

> 공식 문서 기반 분석: Spring Framework 7.0.2

---

## 1. 왜 이 기술이 필요한가? (실무 사례)

### 실무 시나리오

```
[문제 상황]
- 모바일 앱 v1.0: API v1 사용 (2023년 출시)
- 모바일 앱 v2.0: API v2 사용 (2024년 출시)
- 현재: API v1을 폐기하고 싶지만, 아직 v1.0 앱 사용자가 30% 존재

[요구사항]
1. API v1 사용자에게 "곧 폐기됩니다" 경고 전달
2. 정확한 폐기 예정일(Sunset Date) 안내
3. 마이그레이션 가이드 링크 제공
4. 표준화된 HTTP 헤더로 클라이언트가 자동 처리 가능
```

### 왜 StandardApiVersionDeprecationHandler인가?

| 접근 방식               | 문제점                                |
| ----------------------- | ------------------------------------- |
| 응답 본문에 경고 메시지 | 클라이언트 코드 수정 필요, 비표준     |
| 커스텀 헤더             | 클라이언트마다 다른 구현, 비표준      |
| **RFC 9745 + RFC 8594** | **표준화, 자동 처리 가능, 도구 지원** |

---

## 2. 어떻게 동작하는가? (내부 메커니즘)

### 2.1 클래스 구조

```
StandardApiVersionDeprecationHandler
├── implements ApiVersionDeprecationHandler
├── ApiVersionParser<?> parser (기본: SemanticApiVersionParser)
├── Map<Comparable<?>, VersionSpec> deprecatedVersions
└── inner class VersionSpec
    ├── deprecationDate: Instant
    ├── sunsetDate: Instant
    ├── deprecationLink: URI
    └── sunsetLink: URI
```

### 2.2 핵심 메서드

```java
// ┌─────────────────────────────────────────────────────────────┐
// │  StandardApiVersionDeprecationHandler 메서드                 │
// └─────────────────────────────────────────────────────────────┘

// 1. 버전을 deprecated로 설정 → VersionSpec 반환
public VersionSpec configureVersion(String version)

// 2. 요청 처리 시 deprecated 버전 체크 및 헤더 설정
public void handleVersion(
        Comparable<?> requestVersion,
        Object handler,
        HttpServletRequest request,
        HttpServletResponse response
)

// ┌─────────────────────────────────────────────────────────────┐
// │  VersionSpec 메서드 (Fluent API - 체이닝 필수!)              │
// └─────────────────────────────────────────────────────────────┘

// Deprecation 시작 시점 설정
public VersionSpec deprecatedAs(Instant deprecationDate)

// Sunset(완전 폐기) 예정 시점 설정  
public VersionSpec sunsetOn(Instant sunsetDate)

// Deprecation 상세 정보 링크 설정 (마이그레이션 가이드 등)
public VersionSpec withDeprecationLink(URI deprecationLink)

// Sunset 상세 정보 링크 설정
public VersionSpec withSunsetLink(URI sunsetLink)
```

> **중요**: `VersionSpec`은 `configureVersion()`의 반환값으로만 얻을 수 있으며,  
> 반드시 **Fluent API 체이닝** 방식으로 사용해야 합니다!

### 2.3 RFC 표준 헤더

| 헤더          | RFC      | 용도             | 예시 값                                                      |
| ------------- | -------- | ---------------- | ------------------------------------------------------------ |
| `Deprecation` | RFC 9745 | Deprecation 날짜 | `@1640995200` (Unix timestamp)                               |
| `Sunset`      | RFC 8594 | 완전 폐기 날짜   | `Sat, 01 Jul 2025 00:00:00 GMT`                              |
| `Link`        | RFC 8288 | 상세 정보 링크   | `<https://api.example.com/docs/migration>; rel="deprecation"` |

### 2.4 요청 처리 흐름

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Request Flow                                     │
└─────────────────────────────────────────────────────────────────────────┘

  [Client Request]
        │
        │ X-API-Version: 1.0
        ▼
  ┌─────────────────┐
  │  DispatcherServlet  │
  └─────────┬───────┘
            │
            ▼
  ┌─────────────────────────────────────┐
  │     ApiVersionStrategy              │
  │  ┌─────────────────────────────┐   │
  │  │ 1. ApiVersionResolver       │   │  ← 버전 추출: "1.0"
  │  │    (HeaderApiVersionResolver)│   │
  │  └─────────────────────────────┘   │
  │  ┌─────────────────────────────┐   │
  │  │ 2. ApiVersionParser         │   │  ← 파싱: SemanticVersion(1, 0, 0)
  │  │    (SemanticApiVersionParser)│   │
  │  └─────────────────────────────┘   │
  │  ┌─────────────────────────────┐   │
  │  │ 3. ApiVersionDeprecationHandler │ ← 📌 여기서 처리!
  │  │    (StandardApiVersion...)  │   │
  │  └─────────────────────────────┘   │
  └─────────────────┬───────────────────┘
                    │
                    ▼
  ┌─────────────────────────────────────┐
  │  StandardApiVersionDeprecationHandler │
  │                                       │
  │  if (deprecatedVersions.contains(v)) │
  │    → response.setHeader("Deprecation", ...)   │
  │    → response.setHeader("Sunset", ...)        │
  │    → response.addHeader("Link", ...)          │
  │  }                                    │
  └─────────────────┬───────────────────┘
                    │
                    ▼
  ┌─────────────────┐
  │   Controller    │  @GetMapping(version = "1.0")
  └─────────────────┘
        │
        ▼
  [Response with Deprecation Headers]
  
  HTTP/1.1 200 OK
  Deprecation: @1704067200
  Sunset: Sat, 01 Jul 2025 00:00:00 GMT
  Link: <https://api.example.com/migration>; rel="deprecation"
  Link: <https://api.example.com/sunset-info>; rel="sunset"
  Content-Type: application/json
  
  {"data": "..."}
```

---

## 3. 실전 코드 예제

### 3.1 프로젝트 구조

```
src/main/java/com/example/
├── config/
│   └── WebMvcConfig.java               # API 버전 설정
├── controller/
│   └── ProductController.java          # 버전별 엔드포인트
├── dto/
│   ├── ProductV1Response.java          # v1 응답 DTO
│   ├── ProductV2Response.java          # v2 응답 DTO
│   ├── ProductListV2Response.java      # v2 목록 응답 DTO
│   └── CreateProductRequest.java       # 상품 생성 요청 DTO
├── exception/
│   └── ProductNotFoundException.java   # 상품 미발견 예외
└── service/
    └── ProductService.java             # 비즈니스 로직
```

### 3.2 WebMvcConfig.java - 핵심 설정

```java
package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 버전 추출 방식 설정 (여러 방식 동시 지원 가능)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 방법 1: 헤더 기반 (권장 - RESTful)
        configurer.useRequestHeader("X-API-Version");
        
        // 방법 2: 쿼리 파라미터 (디버깅 편의)
        // configurer.useQueryParam("api-version");
        
        // 방법 3: Accept 헤더의 미디어 타입 파라미터
        // configurer.useMediaTypeParameter(
        //     MediaType.APPLICATION_JSON, 
        //     "version"
        // );
        
        // 방법 4: URL 경로 (레거시 시스템 호환)
        // configurer.usePathSegment(0);  // /{version}/products
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 버전 요구사항 설정
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 기본 버전 설정 (버전 미지정 시 사용)
        configurer.setDefaultVersion("2.0");
        
        // 버전 필수 여부 (defaultVersion 설정 시 자동으로 false)
        // configurer.setVersionRequired(false);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 지원 버전 명시 (선택적)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 명시적으로 지원하는 버전 추가
        configurer.addSupportedVersions("1.0", "1.1", "2.0");
        
        // 컨트롤러 매핑에서 자동 감지 여부 (기본: true)
        // configurer.detectSupportedVersions(true);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 4. Deprecation Handler 설정 ★ 핵심 ★
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        configurer.setDeprecationHandler(createDeprecationHandler());
    }
    
    /**
     * Deprecation Handler 생성
     * 
     * RFC 9745 (Deprecation Header)
     * RFC 8594 (Sunset Header)
     */
    private StandardApiVersionDeprecationHandler createDeprecationHandler() {
        StandardApiVersionDeprecationHandler handler = 
            new StandardApiVersionDeprecationHandler();
        
        // ─────────────────────────────────────────────────────────────
        // API v1.0 Deprecation 설정
        // ─────────────────────────────────────────────────────────────
        handler.configureVersion("1.0")
            // Deprecation 시작일 (이미 deprecated된 상태)
            .deprecatedAs(
                LocalDate.of(2024, 1, 1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
            // Sunset(완전 폐기) 예정일
            .sunsetOn(
                LocalDate.of(2025, 7, 1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
            // Deprecation 상세 정보 링크 (마이그레이션 가이드)
            .withDeprecationLink(
                URI.create("https://api.example.com/docs/v1-to-v2-migration")
            )
            // Sunset 상세 정보 링크
            .withSunsetLink(
                URI.create("https://api.example.com/docs/v1-sunset-notice")
            );
        
        // ─────────────────────────────────────────────────────────────
        // API v1.1 Deprecation 설정 (단계적 폐기)
        // ─────────────────────────────────────────────────────────────
        handler.configureVersion("1.1")
            .deprecatedAs(
                LocalDate.of(2024, 6, 1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
            .sunsetOn(
                LocalDate.of(2025, 12, 31)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
            )
            .withDeprecationLink(
                URI.create("https://api.example.com/docs/v1.1-migration")
            );
        
        return handler;
    }
}
```

### 3.3 ProductController.java - 버전별 엔드포인트

```java
package com.example.controller;

import com.example.dto.ProductV1Response;
import com.example.dto.ProductV2Response;
import com.example.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 상품 API 컨트롤러
 * 
 * 버전별 분기 처리 예시
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v1.0 - Deprecated (2025-07-01 폐기 예정)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * 상품 목록 조회 (v1.0)
     * 
     * @deprecated since 2.0 - v2.0 사용 권장. 2025-07-01 폐기 예정.
     * @see #getProductsV2(int, int)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(version = "1.0")
    public ResponseEntity<List<ProductV1Response>> getProductsV1() {
        // v1에서는 단순한 필드만 제공
        List<ProductV1Response> products = productService.getProductsV1();
        return ResponseEntity.ok(products);
    }

    /**
     * 상품 상세 조회 (v1.0)
     * 
     * @deprecated since 2.0 - v2.0 사용 권장. 2025-07-01 폐기 예정.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(value = "/{id}", version = "1.0")
    public ResponseEntity<ProductV1Response> getProductV1(@PathVariable Long id) {
        ProductV1Response product = productService.getProductByIdV1(id);
        return ResponseEntity.ok(product);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v1.1 - Deprecated (2025-12-31 폐기 예정)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * 상품 목록 조회 (v1.1)
     * 
     * v1.0 대비 개선사항: 카테고리 필드 추가
     * 
     * @deprecated since 2.0 - v2.0 사용 권장. 2025-12-31 폐기 예정.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @GetMapping(version = "1.1")
    public ResponseEntity<List<ProductV1Response>> getProductsV1_1() {
        // v1.1에서는 카테고리 포함
        List<ProductV1Response> products = productService.getProductsV1WithCategory();
        return ResponseEntity.ok(products);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v2.0 - Current (권장 버전)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * 상품 목록 조회 (v2.0) - 권장
     * 
     * 개선사항:
     * - 페이지네이션 지원
     * - 풍부한 메타데이터 제공
     * - HATEOAS 링크 포함
     */
    @GetMapping(version = "2.0")
    public ResponseEntity<ProductListV2Response> getProductsV2(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
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
     * 
     * v1에서는 지원하지 않던 기능
     */
    @PostMapping(version = "2.0")
    public ResponseEntity<ProductV2Response> createProduct(
            @RequestBody CreateProductRequest request) {
        ProductV2Response product = productService.createProduct(request);
        return ResponseEntity
            .created(URI.create("/api/products/" + product.getId()))
            .body(product);
    }
}
```

### 3.4 ProductService.java - 비즈니스 로직

```java
package com.example.service;

import com.example.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
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
    private final AtomicLong idGenerator = new AtomicLong(1);

    public ProductService() {
        // 샘플 데이터 초기화
        initSampleData();
    }

    private void initSampleData() {
        createProductInternal("노트북 Pro", new BigDecimal("1500000"), "KRW", 1L, "전자기기");
        createProductInternal("무선 마우스", new BigDecimal("45000"), "KRW", 1L, "전자기기");
        createProductInternal("기계식 키보드", new BigDecimal("120000"), "KRW", 1L, "전자기기");
        createProductInternal("모니터 27인치", new BigDecimal("350000"), "KRW", 1L, "전자기기");
        createProductInternal("USB-C 허브", new BigDecimal("55000"), "KRW", 2L, "액세서리");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v1.0 용 메서드 (레거시 형식)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
                entity.price().intValue()  // BigDecimal → int 변환 (정밀도 손실)
            ))
            .toList();
    }

    /**
     * 상품 상세 조회 (v1.0)
     */
    public ProductV1Response getProductByIdV1(Long id) {
        ProductEntity entity = findByIdOrThrow(id);
        return ProductV1Response.withoutCategory(
            entity.id(),
            entity.name(),
            entity.price().intValue()
        );
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v1.1 용 메서드 (카테고리 추가)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
                entity.categoryName()  // v1.1에서 카테고리 추가
            ))
            .toList();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API v2.0 용 메서드 (확장된 형식)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

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
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return new ProductListV2Response(
            content,
            new ProductListV2Response.PageInfo(
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Private 헬퍼 메서드
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private ProductEntity findByIdOrThrow(Long id) {
        ProductEntity entity = products.get(id);
        if (entity == null) {
            throw new ProductNotFoundException("Product not found: " + id);
        }
        return entity;
    }

    private ProductEntity createProductInternal(
            String name, 
            BigDecimal price, 
            String currency,
            Long categoryId, 
            String categoryName) {
        
        Long id = idGenerator.getAndIncrement();
        Instant now = Instant.now();
        
        ProductEntity entity = new ProductEntity(
            id,
            name,
            price,
            currency,
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
                "en", entity.name() + " (EN)"  // 실제로는 번역 데이터 사용
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

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // 내부 엔티티 및 예외
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 내부 엔티티 (실제로는 JPA Entity 사용)
     */
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
    ) {}
}

// ProductNotFoundException.java
package com.example.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String message) {
        super(message);
    }
}

// CreateProductRequest.java
package com.example.dto;

import java.math.BigDecimal;

public record CreateProductRequest(
    String name,
    BigDecimal price,
    String currency,
    Long categoryId,
    String categoryName
) {}
```

### 3.5 DTO 클래스

```java
// ProductV1Response.java
package com.example.dto;

/**
 * 상품 응답 DTO (API v1.x)
 * 
 * @deprecated since 2.0 - v2.0 사용 권장. 2025-07-01 폐기 예정.
 * @see ProductV2Response
 */
@Deprecated(since = "2.0", forRemoval = true)
public record ProductV1Response(
    Long id,
    String name,
    Integer price,          // 정수형 가격 (v1 한계)
    String category         // v1.1에서 추가
) {
    // v1.0 호환성을 위한 팩토리 메서드
    public static ProductV1Response withoutCategory(Long id, String name, Integer price) {
        return new ProductV1Response(id, name, price, null);
    }
}

// ProductV2Response.java
package com.example.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 상품 응답 DTO (API v2.0)
 * 
 * v1 대비 개선사항:
 * - BigDecimal 가격 (정밀도 향상)
 * - 다국어 지원 (translations)
 * - 메타데이터 확장
 * - HATEOAS 링크
 */
public record ProductV2Response(
    Long id,
    String name,
    BigDecimal price,                       // 정밀한 가격 표현
    String currency,                         // 통화 정보 추가
    CategoryInfo category,                   // 카테고리 상세 정보
    Map<String, String> translations,        // 다국어 지원
    ProductMetadata metadata,                // 확장된 메타데이터
    List<Link> links                         // HATEOAS 링크
) {
    public record CategoryInfo(
        Long id,
        String name,
        String path
    ) {}
    
    public record ProductMetadata(
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        Integer viewCount
    ) {}
    
    public record Link(
        String rel,
        String href,
        String method
    ) {}
}

// ProductListV2Response.java
package com.example.dto;

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
```

---

## 4. @Deprecated 어노테이션 올바른 사용법

### 4.1 필드 정의

```java
@Deprecated(since = "X.X", forRemoval = true/false)
```

| 필드         | 의미                              | 핵심 질문                                  |
| ------------ | --------------------------------- | ------------------------------------------ |
| `since`      | **언제부터** deprecated 되었는가? | "이 API가 deprecated로 **선언된** 버전은?" |
| `forRemoval` | 향후 **제거 예정**인가?           | "나중에 삭제될 것인가?"                    |

### 4.2 실제 시나리오

```
Timeline:
─────────────────────────────────────────────────────────────────►
   v1.0 출시      v1.1 출시       v2.0 출시
   (2023.01)     (2023.06)       (2024.01)
                                    │
                                    └── 이 시점에 v1.x를 deprecated로 선언
```

### 4.3 올바른 값 선택

```java
// ✅ 올바른 예시
@Deprecated(since = "2.0", forRemoval = true)
public record ProductV1Response(...) {}
```

| 값              | 의미                                       | 판단                                  |
| --------------- | ------------------------------------------ | ------------------------------------- |
| `since = "1.0"` | "v1.0 출시 때부터 deprecated"              | ❌ 출시하자마자 deprecated? 말이 안 됨 |
| `since = "1.1"` | "v1.1 출시 때부터 deprecated"              | ❌ v1.1도 v1 계열, 아직 v2가 없었음    |
| `since = "2.0"` | "v2.0 출시하면서 v1.x를 deprecated로 선언" | ✅ 정답!                               |

### 4.4 Java 표준 라이브러리 예시

```java
// java.lang.Thread - Java 1.2에서 deprecated 선언
@Deprecated(since = "1.2", forRemoval = true)
public final void stop() { ... }

// java.util.Date - Java 1.1에서 deprecated 선언, 제거 예정 아님
@Deprecated(since = "1.1")
public int getYear() { ... }
```

> **핵심**: `since`는 "이 기능이 만들어진 버전"이 아니라, **"deprecated로 선언된 버전"**입니다!

---

## 5. 흔한 실수와 주의사항

### ❌ 실수 1: VersionSpec 메서드명 오해

```java
// ❌ 잘못된 코드 - 이런 메서드 없음!
handler.deprecateVersion("1.0");  // 컴파일 에러!

// ✅ 올바른 코드
handler.configureVersion("1.0")   // 먼저 버전 선택
       .deprecatedAs(instant)     // 그 다음 설정
       .sunsetOn(sunsetInstant);
```

### ❌ 실수 2: 체이닝 없이 별도 호출

```java
// ❌ 잘못된 코드 - VersionSpec 참조를 잃어버림
handler.configureVersion("1.0");
// 아래 호출이 적용될 대상이 없음!
handler.deprecatedAs(instant);  // 컴파일 에러!

// ✅ 올바른 코드 - Fluent API 체이닝 사용
handler.configureVersion("1.0")
       .deprecatedAs(instant)
       .sunsetOn(sunsetInstant);
```

### ❌ 실수 3: YAML 설정 시도

```yaml
# ❌ 잘못된 코드 - YAML로 설정할 수 없음!
spring:
  mvc:
    api-version:
      deprecation:
        v1.0:
          sunset: 2025-07-01
```

```java
// ✅ 올바른 코드 - Java Configuration 필수
@Override
public void configureApiVersioning(ApiVersionConfigurer configurer) {
    configurer.setDeprecationHandler(createDeprecationHandler());
}
```

### ❌ 실수 4: Sunset 날짜만 설정

```java
// ❌ 불완전한 설정 - Deprecation 날짜 없음
handler.configureVersion("1.0")
       .sunsetOn(sunsetInstant);  // Deprecation 언제 시작?

// ✅ 완전한 설정
handler.configureVersion("1.0")
       .deprecatedAs(deprecationStart)  // 언제부터 deprecated?
       .sunsetOn(sunsetInstant)         // 언제 완전 폐기?
       .withDeprecationLink(migrationGuide);  // 어디서 정보 확인?
```

### ❌ 실수 5: Handler 등록 누락

```java
// ❌ 잘못된 코드 - Handler를 만들기만 하고 등록 안 함
@Override
public void configureApiVersioning(ApiVersionConfigurer configurer) {
    configurer.useRequestHeader("X-API-Version");
    
    // Handler 생성했지만...
    StandardApiVersionDeprecationHandler handler = 
        new StandardApiVersionDeprecationHandler();
    handler.configureVersion("1.0").deprecatedAs(instant);
    // 등록 안 함! 동작 안 함!
}

// ✅ 올바른 코드 - 반드시 등록
@Override
public void configureApiVersioning(ApiVersionConfigurer configurer) {
    configurer.useRequestHeader("X-API-Version");
    
    StandardApiVersionDeprecationHandler handler = 
        new StandardApiVersionDeprecationHandler();
    handler.configureVersion("1.0").deprecatedAs(instant);
    
    configurer.setDeprecationHandler(handler);  // 등록 필수!
}
```

---

## 6. 테스트 코드 작성 방법

### 6.1 통합 테스트 (MockMvc)

```java
package com.example.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiVersionDeprecationTest {

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
                    matchesPattern("@\\d+")))  // Unix timestamp 형식
                // Sunset 헤더 검증 (RFC 8594)
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Sunset", 
                    containsString("2025")))
                // Link 헤더 검증
                .andExpect(header().string("Link", 
                    containsString("rel=\"deprecation\"")));
        }

        @Test
        @DisplayName("v1.0 요청 시 Link 헤더에 마이그레이션 가이드 URL이 포함되어야 한다")
        void shouldIncludeMigrationLink() throws Exception {
            mockMvc.perform(get("/api/products")
                    .header("X-API-Version", "1.0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Link", 
                    containsString("v1-to-v2-migration")));
        }

        @Test
        @DisplayName("v1.0 응답 본문은 레거시 형식이어야 한다")
        void shouldReturnLegacyFormat() throws Exception {
            mockMvc.perform(get("/api/products/1")
                    .header("X-API-Version", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").isNumber())  // 정수형
                .andExpect(jsonPath("$.currency").doesNotExist())  // 통화 없음
                .andExpect(jsonPath("$.metadata").doesNotExist());  // 메타데이터 없음
        }
    }

    @Nested
    @DisplayName("Current API v2.0 요청")
    class CurrentV2Tests {

        @Test
        @DisplayName("v2.0 요청 시 Deprecation 헤더가 없어야 한다")
        void shouldNotIncludeDeprecationHeader() throws Exception {
            mockMvc.perform(get("/api/products")
                    .header("X-API-Version", "2.0")
                    .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(header().doesNotExist("Sunset"));
        }

        @Test
        @DisplayName("v2.0 응답 본문은 확장된 형식이어야 한다")
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
        @DisplayName("버전 미지정 시 기본 버전(2.0)이 적용되어야 한다")
        void shouldApplyDefaultVersion() throws Exception {
            mockMvc.perform(get("/api/products")
                    .accept(MediaType.APPLICATION_JSON))
                // 버전 헤더 없이 요청
                .andExpect(status().isOk())
                // Deprecation 헤더 없음 (v2.0이 기본)
                .andExpect(header().doesNotExist("Deprecation"))
                // v2.0 형식 응답
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
```

### 6.2 curl을 이용한 수동 테스트

```bash
#!/bin/bash
# test-api-deprecation.sh

BASE_URL="http://localhost:8080"
ENDPOINT="/api/products"

echo "=========================================="
echo "API Version Deprecation 테스트"
echo "=========================================="

echo ""
echo "1. API v1.0 요청 (Deprecated)"
echo "-------------------------------------------"
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 1.0" \
     -H "Accept: application/json" 2>/dev/null | head -20

echo ""
echo ""
echo "2. API v2.0 요청 (Current)"
echo "-------------------------------------------"
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 2.0" \
     -H "Accept: application/json" 2>/dev/null | head -20

echo ""
echo ""
echo "3. 버전 미지정 요청 (기본 버전 적용)"
echo "-------------------------------------------"
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "Accept: application/json" 2>/dev/null | head -15

echo ""
echo ""
echo "4. 지원하지 않는 버전 요청"
echo "-------------------------------------------"
curl -i -X GET "${BASE_URL}${ENDPOINT}" \
     -H "X-API-Version: 9.9" \
     -H "Accept: application/json" 2>/dev/null | head -10
```

### 6.3 예상 응답

```http
# API v1.0 요청 시 응답 헤더
HTTP/1.1 200 OK
Deprecation: @1704067200
Sunset: Tue, 01 Jul 2025 00:00:00 GMT
Link: <https://api.example.com/docs/v1-to-v2-migration>; rel="deprecation"
Link: <https://api.example.com/docs/v1-sunset-notice>; rel="sunset"
Content-Type: application/json

{"id":1,"name":"상품A","price":10000,"category":null}
```

---

## 7. 프로덕션 고려사항

### 7.1 모니터링 및 알림

```java
@Component
@Slf4j
public class DeprecatedApiMetricsInterceptor implements HandlerInterceptor {

    private final MeterRegistry meterRegistry;

    @Override
    public void afterCompletion(
            HttpServletRequest request, 
            HttpServletResponse response,
            Object handler, 
            Exception ex) {
        
        // Deprecation 헤더가 있으면 deprecated API 호출
        if (response.containsHeader("Deprecation")) {
            String version = request.getHeader("X-API-Version");
            String clientId = request.getHeader("X-Client-ID");
            String endpoint = request.getRequestURI();
            
            // 메트릭 기록
            meterRegistry.counter("api.deprecated.calls",
                "version", version,
                "endpoint", endpoint,
                "client", clientId != null ? clientId : "unknown"
            ).increment();
            
            // 경고 로그
            log.warn("Deprecated API called: version={}, endpoint={}, client={}",
                version, endpoint, clientId);
        }
    }
}
```

### 7.2 클라이언트 알림 시스템

```java
@Component
public class DeprecationNotificationService {

    private final EmailService emailService;
    private final Set<String> notifiedClients = ConcurrentHashMap.newKeySet();

    /**
     * Sunset 30일 전부터 클라이언트에게 이메일 알림
     */
    @Scheduled(cron = "0 0 9 * * MON")  // 매주 월요일 오전 9시
    public void sendDeprecationReminders() {
        LocalDate today = LocalDate.now();
        LocalDate sunsetDate = LocalDate.of(2025, 7, 1);
        long daysUntilSunset = ChronoUnit.DAYS.between(today, sunsetDate);
        
        if (daysUntilSunset <= 30 && daysUntilSunset > 0) {
            // 최근 7일간 v1.0 사용한 클라이언트 조회
            List<ClientInfo> activeV1Clients = 
                clientRepository.findClientsUsingVersion("1.0", 7);
            
            for (ClientInfo client : activeV1Clients) {
                if (!notifiedClients.contains(client.getId())) {
                    emailService.sendDeprecationWarning(
                        client.getEmail(),
                        "API v1.0",
                        sunsetDate,
                        "https://api.example.com/docs/v1-to-v2-migration"
                    );
                    notifiedClients.add(client.getId());
                }
            }
        }
    }
}
```

---

## 8. 핵심 요약

### StandardApiVersionDeprecationHandler 사용법

```java
// 1. Handler 생성 (기본 SemanticApiVersionParser 사용)
StandardApiVersionDeprecationHandler handler =
        new StandardApiVersionDeprecationHandler();

// 2. 버전별 deprecation 설정
handler.configureVersion("버전")
       .deprecatedAs(Instant)        // Deprecation 시작일
       .sunsetOn(Instant)            // 완전 폐기 예정일
       .withDeprecationLink(URI)     // 마이그레이션 가이드 URL
       .withSunsetLink(URI);         // Sunset 정보 URL

// 3. ApiVersionConfigurer에 등록
configurer.setDeprecationHandler(handler);
```

### 응답 헤더 예시

```http
Deprecation: @1704067200
Sunset: Tue, 01 Jul 2025 00:00:00 GMT
Link: <https://docs.example.com/migration>; rel="deprecation"
Link: <https://docs.example.com/sunset>; rel="sunset"
```

### 체크리스트

- [ ] `configureVersion()` → `VersionSpec` 체이닝 방식 사용
- [ ] `deprecatedAs()` + `sunsetOn()` 모두 설정
- [ ] `withDeprecationLink()` 로 마이그레이션 가이드 제공
- [ ] `setDeprecationHandler()` 로 핸들러 등록 확인
- [ ] 테스트로 Deprecation/Sunset 헤더 검증
- [ ] 프로덕션 모니터링 설정