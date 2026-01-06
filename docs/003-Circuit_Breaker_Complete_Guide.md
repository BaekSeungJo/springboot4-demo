# Circuit Breaker (회로 차단기) 패턴 완벽 가이드

> Spring Boot 4.x / Spring Framework 7.x + Resilience4j 기반

---

## 목차

1. [Circuit Breaker란?](#1-circuit-breaker란)
2. [왜 필요한가? (실무 시나리오)](#2-왜-필요한가-실무-시나리오)
3. [동작 원리 상세](#3-동작-원리-상세)
4. [프로젝트 설정](#4-프로젝트-설정)
5. [설정 파라미터 완벽 가이드](#5-설정-파라미터-완벽-가이드)
6. [실전 코드 예제](#6-실전-코드-예제)
7. [Fallback 전략](#7-fallback-전략)
8. [테스트 코드 작성](#8-테스트-코드-작성)
9. [흔한 실수와 주의사항](#9-흔한-실수와-주의사항)
10. [Best Practices](#10-best-practices)
11. [모니터링과 운영](#11-모니터링과-운영)
12. [실습 가이드](#12-실습-가이드)

---

## 1. Circuit Breaker란?

### 1.1 정의

Circuit Breaker(회로 차단기)는 **마이크로서비스 아키텍처에서 장애 전파를 차단**하기 위한 디자인 패턴입니다.

전기 회로의 차단기에서 이름을 따왔습니다:
- 전기 차단기: 과전류 감지 시 회로를 차단하여 화재 방지
- Circuit Breaker 패턴: 서비스 장애 감지 시 요청을 차단하여 시스템 보호

### 1.2 핵심 아이디어

```
"실패할 것이 뻔한 요청은 아예 보내지 말자"
```

외부 서비스가 장애 상태일 때:
- ❌ 계속 요청 → 스레드 대기 → 리소스 고갈 → 전체 시스템 다운
- ✅ 빠른 실패(Fail-Fast) → 즉시 대체 응답 → 시스템 보호

### 1.3 Netflix Hystrix vs Resilience4j

| 항목 | Netflix Hystrix | Resilience4j |
|------|----------------|--------------|
| 상태 | 2018년부터 유지보수 모드 | 활발히 개발 중 |
| Spring Boot 4.x | ❌ 지원 안 함 | ✅ 공식 지원 |
| Java 버전 | Java 8 | Java 17+ |
| 함수형 프로그래밍 | 제한적 | 완전 지원 |
| 메모리 사용량 | 높음 | 낮음 |

> **결론**: Spring Boot 4.x에서는 **Resilience4j**가 유일한 선택입니다.

---

## 2. 왜 필요한가? (실무 시나리오)

### 2.1 문제 상황: 연쇄 장애 (Cascading Failure)

실제 이커머스 시스템을 가정해봅시다:

```
┌─────────┐     ┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│  사용자  │ ──→ │  주문 서비스  │ ──→ │  결제 서비스  │ ──→ │  외부 PG사 ❌ │
└─────────┘     └─────────────┘     └─────────────┘     └──────────────┘
                      │                                    장애 발생!
                      ▼
                ┌─────────────┐
                │  재고 서비스  │
                └─────────────┘
                      │
                      ▼
                ┌─────────────┐
                │  배송 서비스  │
                └─────────────┘
```

**장애 시나리오:**

1. **외부 PG사 장애 발생**
   - 정상 응답 시간: 100ms
   - 장애 시 응답 시간: 30초 (타임아웃)

2. **결제 서비스 영향**
   - 모든 요청이 30초씩 대기
   - 스레드 풀(예: 200개) 빠르게 고갈

3. **주문 서비스 영향**
   - 결제 서비스 호출 대기
   - 주문 서비스 스레드도 고갈

4. **전체 시스템 다운**
   - 재고, 배송 서비스도 응답 불가
   - 신규 사용자 요청 처리 불가
   - **완전한 서비스 중단**

### 2.2 Circuit Breaker가 없을 때 vs 있을 때

#### Circuit Breaker 없이

```
사용자 요청 ──→ 주문 서비스 ──→ 결제 서비스 ──→ PG사 (30초 대기...)
                  │
                  └── 스레드 점유 (30초간 아무것도 못함)
                  
× 200개 스레드 = 전체 시스템 마비
```

#### Circuit Breaker 적용 시

```
사용자 요청 ──→ 주문 서비스 ──→ [Circuit Breaker] ─✗─→ 결제 서비스
                                    │
                                    │ Circuit OPEN!
                                    ▼
                              Fallback 응답 (즉시 반환)
                              "결제가 지연되고 있습니다.
                               잠시 후 재시도해주세요."
                               
✓ 스레드 즉시 반환 → 다른 요청 처리 가능
```

### 2.3 Circuit Breaker의 이점

| 이점 | 설명 |
|------|------|
| **빠른 실패** | 장애 서비스 호출 대신 즉시 Fallback 반환 |
| **리소스 보호** | 스레드, 커넥션 등 리소스 고갈 방지 |
| **장애 격리** | 한 서비스 장애가 전체 시스템에 전파되지 않음 |
| **자동 복구** | 서비스 복구 시 자동으로 정상 상태로 전환 |
| **부하 감소** | 장애 서비스에 추가 부하를 주지 않음 |

---

## 3. 동작 원리 상세

### 3.1 3가지 상태 (State)

Circuit Breaker는 **유한 상태 기계(Finite State Machine)**로 동작합니다.

```
                         실패율 > 임계값
           ┌──────────────────────────────────────┐
           │                                      ▼
       ┌───────┐                             ┌────────┐
       │CLOSED │                             │  OPEN  │
       └───────┘                             └────────┘
           ▲                                      │
           │                                      │ waitDurationInOpenState 경과
           │                                      ▼
           │         성공률 충족            ┌───────────┐
           └─────────────────────────────── │ HALF_OPEN │
                                            └───────────┘
                                                  │
                                                  │ 실패율 > 임계값
                                                  ▼
                                             ┌────────┐
                                             │  OPEN  │
                                             └────────┘
```

#### CLOSED (닫힘) 상태

```
상태: 정상
동작: 모든 요청이 실제 서비스로 전달됨
모니터링: 실패율을 지속적으로 측정
전환 조건: 실패율이 failureRateThreshold 초과 시 → OPEN
```

**예시:**
```
요청 1: 성공 ✓
요청 2: 성공 ✓
요청 3: 실패 ✗
요청 4: 성공 ✓
요청 5: 실패 ✗
... (실패율 40% = 아직 CLOSED 유지)

요청 6: 실패 ✗
요청 7: 실패 ✗
... (실패율 60% > 임계값 50% = OPEN으로 전환!)
```

#### OPEN (열림) 상태

```
상태: 장애 감지됨
동작: 모든 요청이 즉시 실패 (CallNotPermittedException)
       → Fallback 메서드 호출
모니터링: waitDurationInOpenState 시간 경과 대기
전환 조건: 대기 시간 경과 시 → HALF_OPEN
```

**예시:**
```
[Circuit Breaker OPEN]

요청 8: 즉시 차단! → Fallback 응답 (10ms)
요청 9: 즉시 차단! → Fallback 응답 (8ms)
요청 10: 즉시 차단! → Fallback 응답 (12ms)
... (실제 서비스 호출 없이 빠르게 응답)

[30초 경과]
→ HALF_OPEN으로 전환
```

#### HALF_OPEN (반열림) 상태

```
상태: 복구 테스트 중
동작: 제한된 수의 요청만 실제 서비스로 전달
       (permittedNumberOfCallsInHalfOpenState)
모니터링: 테스트 요청의 성공/실패 측정 후 실패율 계산
전환 조건: 
  - 실패율 ≤ failureRateThreshold → CLOSED
  - 실패율 > failureRateThreshold → OPEN
```

**⚠️ 중요: HALF_OPEN에서의 상태 전환 기준**

`permittedNumberOfCallsInHalfOpenState: 3`, `failureRateThreshold: 50%` 일 때:

```
케이스 1: 3개 중 3개 성공 (실패율 0%)
  요청 1: 성공 ✓
  요청 2: 성공 ✓
  요청 3: 성공 ✓
  → 실패율 0% ≤ 50% → CLOSED ✓

케이스 2: 3개 중 2개 성공, 1개 실패 (실패율 33%)
  요청 1: 성공 ✓
  요청 2: 실패 ✗
  요청 3: 성공 ✓
  → 실패율 33% ≤ 50% → CLOSED ✓ (모두 성공 안 해도 됨!)

케이스 3: 3개 중 1개 성공, 2개 실패 (실패율 66%)
  요청 1: 실패 ✗
  요청 2: 성공 ✓
  요청 3: 실패 ✗
  → 실패율 66% > 50% → OPEN ✗
```

**즉시 OPEN 전환 케이스:**

실패율이 threshold를 초과하면 나머지 요청을 기다리지 않고 즉시 OPEN 전환됩니다.

```
failureRateThreshold: 30%, permittedNumberOfCallsInHalfOpenState: 10

요청 1: 실패 ✗
요청 2: 실패 ✗
요청 3: 실패 ✗
요청 4: 성공 ✓
→ 현재 실패율 75% > 30% → 즉시 OPEN! (나머지 6개 기다리지 않음)
```

### 3.2 슬라이딩 윈도우 (Sliding Window)

실패율을 계산하기 위해 **슬라이딩 윈도우** 방식을 사용합니다.

#### COUNT_BASED (개수 기반)

```
slidingWindowType: COUNT_BASED
slidingWindowSize: 10

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ ✓ │ ✓ │ ✗ │ ✓ │ ✗ │ ✓ │ ✓ │ ✗ │ ✓ │ ✓ │  ← 최근 10개 호출
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  1   2   3   4   5   6   7   8   9   10

실패: 3개, 성공: 7개
실패율: 30%
```

새 요청이 오면 가장 오래된 결과가 제거됩니다:

```
새 요청 (실패 ✗) 추가
                                    제거
┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ ✓ │ ✗ │ ✓ │ ✗ │ ✓ │ ✓ │ ✗ │ ✓ │ ✓ │ ✗ │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  ↑
가장 오래된 것 제거

실패율: 40% (4/10)
```

#### TIME_BASED (시간 기반)

```
slidingWindowType: TIME_BASED
slidingWindowSize: 10  # 10초

현재 시간: 10:00:15

10:00:05 ~ 10:00:15 사이의 모든 호출을 집계
┌────────────────────────────────────────┐
│ 10:00:06 ✓  10:00:08 ✗  10:00:10 ✓    │
│ 10:00:11 ✓  10:00:13 ✗  10:00:14 ✓    │
└────────────────────────────────────────┘

실패: 2개, 성공: 4개
실패율: 33%
```

#### 어떤 것을 선택해야 하나?

| 유형 | 장점 | 단점 | 적합한 상황 |
|------|------|------|------------|
| COUNT_BASED | 예측 가능한 샘플 크기 | 트래픽 적으면 갱신 느림 | 일정한 트래픽 |
| TIME_BASED | 실시간 상황 반영 | 메모리 사용량 가변 | 불규칙한 트래픽 |

**권장**: 대부분의 경우 `COUNT_BASED`가 적합합니다.

### 3.3 느린 호출 (Slow Calls) 처리

타임아웃은 아니지만 **비정상적으로 느린 응답**도 장애 징후입니다.

```yaml
slowCallDurationThreshold: 2s    # 2초 이상이면 "느린 호출"
slowCallRateThreshold: 80        # 느린 호출이 80% 이상이면 OPEN
```

**예시:**
```
요청 1: 150ms (정상)
요청 2: 2500ms (느림 🐌)
요청 3: 180ms (정상)
요청 4: 3000ms (느림 🐌)
요청 5: 2800ms (느림 🐌)
... 

느린 호출 비율: 60% → CLOSED 유지
느린 호출 비율: 85% → OPEN 전환!
```

---

## 4. 프로젝트 설정

### 4.1 build.gradle

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.0'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven { url 'https://repo.spring.io/milestone' }
}

dependencies {
    // Spring Boot Starters
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    
    // ⚠️ 중요: AOP 의존성 필수!
    // 이것 없이는 @CircuitBreaker 어노테이션이 동작하지 않습니다
    implementation 'org.springframework.boot:spring-boot-starter-aop'
    
    // Resilience4j
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    
    // 테스트
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

### 4.2 의존성 설명

| 의존성 | 용도 | 필수 여부 |
|--------|------|----------|
| `spring-boot-starter-aop` | @CircuitBreaker 어노테이션 동작 | ⚠️ **필수** |
| `resilience4j-spring-boot3` | Resilience4j 자동 설정 | ⚠️ **필수** |
| `spring-boot-starter-actuator` | 모니터링 엔드포인트 | 권장 |

---

## 5. 설정 파라미터 완벽 가이드

### 5.1 application.yml 전체 설정

```yaml
spring:
  application:
    name: circuit-breaker-demo

# Actuator 설정
management:
  endpoints:
    web:
      exposure:
        include: health,circuitbreakers,circuitbreakerevents,metrics
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true

# Resilience4j Circuit Breaker 설정
resilience4j:
  circuitbreaker:
    # ========================================
    # 공통 설정 (configs)
    # 여러 인스턴스에서 상속받아 사용
    # ========================================
    configs:
      default:
        # --- 슬라이딩 윈도우 설정 ---
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        
        # --- 실패율 임계값 ---
        failureRateThreshold: 50
        
        # --- OPEN 상태 설정 ---
        waitDurationInOpenState: 30s
        automaticTransitionFromOpenToHalfOpenEnabled: true
        
        # --- HALF_OPEN 상태 설정 ---
        permittedNumberOfCallsInHalfOpenState: 3
        
        # --- 느린 호출 설정 ---
        slowCallDurationThreshold: 2s
        slowCallRateThreshold: 80
        
        # --- 예외 처리 ---
        recordExceptions:
          - java.io.IOException
          - java.net.ConnectException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.example.exception.BusinessException
    
    # ========================================
    # 개별 인스턴스 설정
    # ========================================
    instances:
      paymentService:
        baseConfig: default
        failureRateThreshold: 30
        waitDurationInOpenState: 60s
```

### 5.2 각 파라미터 상세 설명

#### 슬라이딩 윈도우 관련

| 파라미터 | 기본값 | 설명 |
|---------|-------|------|
| `slidingWindowType` | COUNT_BASED | 윈도우 유형 (COUNT_BASED / TIME_BASED) |
| `slidingWindowSize` | 100 | 윈도우 크기 (개수 또는 초) |
| `minimumNumberOfCalls` | 100 | 실패율 계산을 시작할 최소 호출 수 |

**minimumNumberOfCalls 이해하기:**

```
설정: minimumNumberOfCalls = 5, failureRateThreshold = 50%

호출 1: 실패 ✗ → 실패율 계산 안 함 (4개 부족)
호출 2: 실패 ✗ → 실패율 계산 안 함 (3개 부족)
호출 3: 실패 ✗ → 실패율 계산 안 함 (2개 부족)
호출 4: 실패 ✗ → 실패율 계산 안 함 (1개 부족)
호출 5: 실패 ✗ → 실패율 100%! → OPEN 전환

※ 주의: 4개 모두 실패해도 CLOSED 유지됨
         5개째에서야 비로소 실패율 계산 시작
```

#### 실패율/상태 전환 관련

| 파라미터 | 기본값 | 설명 |
|---------|-------|------|
| `failureRateThreshold` | 50 | OPEN 전환 실패율 (%) |
| `waitDurationInOpenState` | 60s | OPEN 상태 유지 시간 |
| `automaticTransitionFromOpenToHalfOpenEnabled` | false | 자동 HALF_OPEN 전환 여부 |
| `permittedNumberOfCallsInHalfOpenState` | 10 | HALF_OPEN에서 허용할 호출 수 |

**automaticTransitionFromOpenToHalfOpenEnabled 설명:**

```
true인 경우:
  OPEN 상태에서 waitDuration 경과 시
  → 자동으로 HALF_OPEN 전환 (백그라운드 타이머)

false인 경우:
  OPEN 상태에서 waitDuration 경과 후
  → 다음 요청이 들어올 때 HALF_OPEN 전환
```

#### 느린 호출 관련

| 파라미터 | 기본값 | 설명 |
|---------|-------|------|
| `slowCallDurationThreshold` | 60s | "느린 호출" 판단 기준 시간 |
| `slowCallRateThreshold` | 100 | OPEN 전환 느린 호출 비율 (%) |

#### 예외 처리 관련

| 파라미터 | 설명 |
|---------|------|
| `recordExceptions` | 실패로 기록할 예외 목록 |
| `ignoreExceptions` | 실패로 기록하지 않을 예외 목록 |
| `recordFailurePredicate` | 커스텀 실패 판단 로직 (코드로 정의) |

### 5.3 서비스 특성별 권장 설정

#### 결제/금융 서비스 (매우 민감)

```yaml
paymentService:
  failureRateThreshold: 20          # 20%만 실패해도 차단
  waitDurationInOpenState: 120s     # 2분간 대기 (신중한 복구)
  slowCallDurationThreshold: 1s     # 1초 이상이면 느린 호출
  slowCallRateThreshold: 50         # 느린 호출 50%면 차단
  minimumNumberOfCalls: 10          # 충분한 샘플 확보
```

#### 주문/재고 서비스 (중간)

```yaml
orderService:
  failureRateThreshold: 50
  waitDurationInOpenState: 30s
  slowCallDurationThreshold: 3s
  slowCallRateThreshold: 80
```

#### 알림/로깅 서비스 (관대)

```yaml
notificationService:
  failureRateThreshold: 80          # 80% 실패까지 허용
  waitDurationInOpenState: 10s      # 빠른 복구 시도
  slowCallDurationThreshold: 10s
  slowCallRateThreshold: 90
```

---

## 6. 실전 코드 예제

### 6.1 예외 클래스 정의

먼저 Circuit Breaker가 구분할 예외를 정의합니다.

#### ServiceException (실패로 기록)

```java
package com.example.circuitbreaker.exception;

/**
 * 서비스 예외 - Circuit Breaker가 "실패"로 카운트
 * 
 * 사용 상황:
 * - 외부 서비스 응답 없음
 * - 네트워크 연결 실패
 * - 서버 내부 오류 (5xx)
 * - 타임아웃
 */
public class ServiceException extends RuntimeException {
    
    private final String serviceName;
    private final String errorCode;
    
    public ServiceException(String serviceName, String message) {
        super(message);
        this.serviceName = serviceName;
        this.errorCode = "SERVICE_ERROR";
    }
    
    public ServiceException(String serviceName, String errorCode, String message) {
        super(message);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
    }
    
    // Getters...
}
```

#### BusinessException (무시)

```java
package com.example.circuitbreaker.exception;

/**
 * 비즈니스 예외 - Circuit Breaker가 무시 (실패로 카운트 안 함)
 * 
 * 왜 무시해야 하는가?
 * - 잔액 부족, 재고 없음 등은 "시스템 장애"가 아님
 * - 비즈니스 로직의 정상적인 거부 응답
 * - 이것으로 Circuit을 열면 정상 요청도 차단됨
 * 
 * 사용 상황:
 * - 잔액 부족
 * - 재고 없음
 * - 중복 요청
 * - 권한 없음
 */
public class BusinessException extends RuntimeException {
    
    private final String errorCode;
    
    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
    
    // 팩토리 메서드
    public static BusinessException insufficientBalance() {
        return new BusinessException("INSUFFICIENT_BALANCE", "잔액이 부족합니다");
    }
    
    public static BusinessException outOfStock() {
        return new BusinessException("OUT_OF_STOCK", "재고가 부족합니다");
    }
}
```

### 6.2 외부 서비스 클라이언트

```java
package com.example.circuitbreaker.client;

import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * 외부 결제 게이트웨이 클라이언트
 * 
 * 실제 프로덕션에서는 RestClient 또는 WebClient 사용
 * 여기서는 테스트를 위해 장애 시뮬레이션 기능 포함
 */
@Component
public class PaymentGatewayClient {
    
    // 장애 시뮬레이션 플래그
    private volatile boolean forceFailure = false;
    private volatile long delayMs = 0;
    
    public PaymentResponse processPayment(PaymentRequest request) {
        // 지연 시뮬레이션
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // 실패 시뮬레이션
        if (forceFailure) {
            throw new ServiceException("PaymentGateway", 
                "PG사 서버 응답 없음");
        }
        
        // 정상 응답
        return new PaymentResponse(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            request.orderId(),
            "COMPLETED"
        );
    }
    
    // 테스트용 설정 메서드
    public void setForceFailure(boolean force) {
        this.forceFailure = force;
    }
    
    public void setDelayMs(long ms) {
        this.delayMs = ms;
    }
    
    public void reset() {
        this.forceFailure = false;
        this.delayMs = 0;
    }
}
```

### 6.3 Circuit Breaker 적용 서비스 (핵심!)

```java
package com.example.circuitbreaker.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 결제 서비스 - Circuit Breaker 패턴 적용
 */
@Service
public class PaymentService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    
    private final PaymentGatewayClient client;
    private final CircuitBreakerRegistry registry;
    
    public PaymentService(PaymentGatewayClient client, 
                          CircuitBreakerRegistry registry) {
        this.client = client;
        this.registry = registry;
    }
    
    // ================================================================
    // 방법 1: 어노테이션 기반 (권장)
    // ================================================================
    
    /**
     * @CircuitBreaker 어노테이션으로 Circuit Breaker 적용
     * 
     * @param name - application.yml에 정의된 인스턴스 이름
     * @param fallbackMethod - 실패 시 호출할 메서드 이름
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("결제 처리 시작: orderId={}", request.orderId());
        
        // 외부 서비스 호출
        PaymentResponse response = client.processPayment(request);
        
        log.info("결제 완료: txnId={}", response.transactionId());
        return response;
    }
    
    /**
     * Fallback 메서드
     * 
     * ⚠️ 중요한 규칙:
     * 1. 원본 메서드와 동일한 파라미터 + Throwable 추가
     * 2. 반환 타입 동일
     * 3. 같은 클래스에 정의
     */
    private PaymentResponse paymentFallback(PaymentRequest request, 
                                             Throwable throwable) {
        log.warn("Fallback 실행: {}", throwable.getMessage());
        
        // Circuit Breaker가 OPEN 상태인 경우
        if (throwable instanceof CallNotPermittedException) {
            log.warn("Circuit OPEN - 요청 차단됨");
            return PaymentResponse.pending(request.orderId(),
                "결제 시스템 과부하 - 잠시 후 재시도해주세요");
        }
        
        // 기타 예외
        return PaymentResponse.pending(request.orderId(),
            "결제 처리 중 오류 - 자동 재시도됩니다");
    }
    
    // ================================================================
    // 방법 2: 프로그래밍 방식
    // ================================================================
    
    /**
     * Registry에서 CircuitBreaker 인스턴스를 직접 사용
     * 
     * 언제 사용하나?
     * - 동적으로 설정을 변경해야 할 때
     * - 조건부 Circuit Breaker 적용
     * - 더 세밀한 제어가 필요할 때
     */
    public PaymentResponse processPaymentProgrammatic(PaymentRequest request) {
        // CircuitBreaker 인스턴스 가져오기
        CircuitBreaker cb = registry.circuitBreaker("paymentService");
        
        log.info("현재 상태: {}", cb.getState());
        
        try {
            // executeSupplier로 실행
            return cb.executeSupplier(() -> 
                client.processPayment(request));
                
        } catch (CallNotPermittedException e) {
            // Circuit OPEN
            return PaymentResponse.pending(request.orderId(),
                "서비스 일시 중단");
        } catch (Exception e) {
            return PaymentResponse.error(request.orderId(),
                e.getMessage());
        }
    }
    
    // ================================================================
    // 모니터링 메서드
    // ================================================================
    
    /**
     * Circuit Breaker 상태 조회
     */
    public CircuitBreakerStatus getStatus() {
        CircuitBreaker cb = registry.circuitBreaker("paymentService");
        CircuitBreaker.Metrics m = cb.getMetrics();
        
        return new CircuitBreakerStatus(
            cb.getState().name(),
            m.getFailureRate(),
            m.getSlowCallRate(),
            m.getNumberOfSuccessfulCalls(),
            m.getNumberOfFailedCalls(),
            m.getNumberOfNotPermittedCalls()
        );
    }
    
    public record CircuitBreakerStatus(
        String state,
        float failureRate,
        float slowCallRate,
        int successCalls,
        int failedCalls,
        long blockedCalls
    ) {}
}
```

### 6.4 REST Controller

```java
package com.example.circuitbreaker.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    
    private final PaymentService paymentService;
    private final PaymentGatewayClient client;
    
    // 생성자 주입...
    
    /**
     * 결제 요청
     * 
     * curl -X POST http://localhost:8080/api/payments \
     *   -H "Content-Type: application/json" \
     *   -d '{"orderId":"ORD-001","amount":10000}'
     */
    @PostMapping
    public PaymentResponse processPayment(@RequestBody PaymentRequest request) {
        return paymentService.processPayment(request);
    }
    
    /**
     * Circuit Breaker 상태 조회
     * 
     * curl http://localhost:8080/api/payments/circuit-breaker/status
     */
    @GetMapping("/circuit-breaker/status")
    public CircuitBreakerStatus getStatus() {
        return paymentService.getStatus();
    }
    
    /**
     * 강제 실패 모드 설정 (테스트용)
     * 
     * curl -X POST "http://localhost:8080/api/payments/simulate/failure?enable=true"
     */
    @PostMapping("/simulate/failure")
    public String setFailure(@RequestParam boolean enable) {
        client.setForceFailure(enable);
        return "실패 모드: " + (enable ? "ON" : "OFF");
    }
    
    /**
     * 설정 초기화
     * 
     * curl -X POST http://localhost:8080/api/payments/reset
     */
    @PostMapping("/reset")
    public String reset() {
        client.reset();
        return "초기화 완료";
    }
}
```

---

## 7. Fallback 전략

### 7.1 Fallback이란?

Circuit Breaker가 요청을 차단할 때 (OPEN 상태) 또는 예외 발생 시 **대체 응답을 제공**하는 메커니즘입니다.

### 7.2 Fallback 메서드 규칙

```java
// 원본 메서드
@CircuitBreaker(name = "myService", fallbackMethod = "myFallback")
public Response doSomething(Request request) {
    // ...
}

// ✅ 올바른 Fallback - 동일 파라미터 + Throwable
private Response myFallback(Request request, Throwable throwable) {
    // ...
}

// ✅ 특정 예외 타입으로 오버로딩 가능
private Response myFallback(Request request, CallNotPermittedException e) {
    // Circuit OPEN 전용 처리
}

private Response myFallback(Request request, TimeoutException e) {
    // 타임아웃 전용 처리
}

// ❌ 잘못된 Fallback - Throwable 누락
private Response myFallback(Request request) {
    // 동작하지 않음!
}
```

### 7.3 상황별 Fallback 전략

#### 전략 1: 대기열 저장 (결제, 주문 등)

```java
private PaymentResponse paymentFallback(PaymentRequest request, 
                                         Throwable throwable) {
    // 1. 대기열에 저장
    PendingPayment pending = new PendingPayment(
        request, 
        LocalDateTime.now(),
        throwable.getMessage()
    );
    messageQueue.send(pending);
    
    // 2. 사용자에게 안내
    return PaymentResponse.builder()
        .status("PENDING")
        .message("결제가 지연되고 있습니다. 완료 시 알림을 보내드립니다.")
        .build();
}
```

#### 전략 2: 캐시 반환 (조회 API)

```java
private List<Product> getProductsFallback(String category, 
                                           Throwable throwable) {
    log.warn("상품 조회 실패, 캐시 반환");
    
    // 캐시에서 반환
    return cacheService.getProducts(category)
        .orElse(Collections.emptyList());
}
```

#### 전략 3: 기본값 반환

```java
private UserProfile getUserProfileFallback(String userId, 
                                            Throwable throwable) {
    // 기본 프로필 반환
    return UserProfile.defaultProfile(userId);
}
```

#### 전략 4: 대체 서비스 호출

```java
private PaymentResponse paymentFallback(PaymentRequest request, 
                                         Throwable throwable) {
    log.warn("주 PG사 실패, 백업 PG사 시도");
    
    try {
        return backupPaymentGateway.process(request);
    } catch (Exception e) {
        // 백업도 실패하면 대기열 저장
        return saveToQueue(request);
    }
}
```

#### 전략 5: 예외 전파 (필수 작업)

```java
private void criticalOperationFallback(Request request, 
                                        Throwable throwable) {
    // 필수 작업은 실패를 명확히 알려야 함
    throw new ServiceUnavailableException(
        "서비스를 일시적으로 이용할 수 없습니다. " +
        "잠시 후 다시 시도해주세요."
    );
}
```

### 7.4 예외 타입별 Fallback 분기

```java
@CircuitBreaker(name = "paymentService", fallbackMethod = "fallback")
public PaymentResponse processPayment(PaymentRequest request) {
    return client.process(request);
}

// 예외 타입별로 여러 Fallback 정의 (오버로딩)
// 가장 구체적인 예외 타입이 먼저 매칭됨

// 1. Circuit Breaker OPEN 상태
private PaymentResponse fallback(PaymentRequest request, 
                                  CallNotPermittedException e) {
    log.warn("Circuit OPEN - 빠른 실패");
    return PaymentResponse.circuitOpen();
}

// 2. 타임아웃
private PaymentResponse fallback(PaymentRequest request, 
                                  TimeoutException e) {
    log.warn("타임아웃 발생");
    return PaymentResponse.timeout();
}

// 3. 비즈니스 예외 (그대로 전파)
private PaymentResponse fallback(PaymentRequest request, 
                                  BusinessException e) {
    throw e;  // 비즈니스 예외는 사용자에게 전달
}

// 4. 기타 모든 예외
private PaymentResponse fallback(PaymentRequest request, 
                                  Throwable throwable) {
    log.error("예상치 못한 오류", throwable);
    return PaymentResponse.error();
}
```

---

## 8. 테스트 코드 작성

### 8.1 테스트 시나리오

| 시나리오 | 검증 내용 |
|---------|----------|
| CLOSED 상태 정상 처리 | 요청이 실제 서비스로 전달됨 |
| 실패율 초과 → OPEN | 임계값 초과 시 상태 전환 |
| OPEN 상태 Fallback | 즉시 Fallback 반환, 실제 호출 없음 |
| HALF_OPEN 성공 → CLOSED | 테스트 요청 성공 시 복구 |
| HALF_OPEN 실패 → OPEN | 테스트 요청 실패 시 다시 차단 |
| 느린 호출 감지 | slowCallRateThreshold 동작 |
| 비즈니스 예외 무시 | 실패로 카운트되지 않음 |

### 8.2 통합 테스트 코드

```java
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerTest {
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private PaymentGatewayClient client;
    
    @Autowired
    private CircuitBreakerRegistry registry;
    
    private CircuitBreaker circuitBreaker;
    
    @BeforeEach
    void setUp() {
        client.reset();
        circuitBreaker = registry.circuitBreaker("paymentService");
        circuitBreaker.reset();
    }
    
    @Test
    @Order(1)
    @DisplayName("CLOSED 상태에서 정상 처리")
    void testClosedState() {
        // given
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
        
        // when
        PaymentResponse response = paymentService.processPayment(
            new PaymentRequest("ORD-001", 10000));
        
        // then
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }
    
    @Test
    @Order(2)
    @DisplayName("실패율 초과 시 OPEN 전환")
    void testTransitionToOpen() {
        // given: 강제 실패 모드
        client.setForceFailure(true);
        
        // when: 충분한 실패 발생 (minimumNumberOfCalls 이상)
        for (int i = 0; i < 10; i++) {
            paymentService.processPayment(
                new PaymentRequest("FAIL-" + i, 10000));
        }
        
        // then
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);
    }
    
    @Test
    @Order(3)
    @DisplayName("OPEN 상태에서 Fallback 반환")
    void testOpenStateFallback() {
        // given: OPEN 상태로 전환
        circuitBreaker.transitionToOpenState();
        int callsBefore = client.getTotalCalls();
        
        // when
        PaymentResponse response = paymentService.processPayment(
            new PaymentRequest("ORD-002", 10000));
        
        // then
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(client.getTotalCalls()).isEqualTo(callsBefore);  // 호출 없음
    }
    
    @Test
    @Order(4)
    @DisplayName("HALF_OPEN에서 실패율이 임계값 이하면 CLOSED 복귀")
    void testHalfOpenSuccess() {
        // given
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        client.setForceFailure(false);  // 정상 동작
        
        // when: permittedNumberOfCallsInHalfOpenState만큼 요청
        // 실패율이 failureRateThreshold 이하면 CLOSED로 전환
        for (int i = 0; i < 3; i++) {
            paymentService.processPayment(
                new PaymentRequest("HALF-" + i, 10000));
        }
        
        // then: 실패율 0% ≤ 50% → CLOSED
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }
    
    @Test
    @Order(5)
    @DisplayName("비즈니스 예외는 실패로 카운트되지 않음")
    void testBusinessExceptionIgnored() {
        // given: 비즈니스 예외 발생하는 요청
        int failedBefore = circuitBreaker.getMetrics().getNumberOfFailedCalls();
        
        // when
        try {
            paymentService.processPayment(
                new PaymentRequest("INVALID", -1000));  // 음수 금액
        } catch (BusinessException e) {
            // 예상된 예외
        }
        
        // then: 실패 카운트 증가 없음
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
            .isEqualTo(failedBefore);
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }
}
```

---

## 9. 흔한 실수와 주의사항

### 9.1 ❌ Fallback 메서드 시그니처 오류

```java
// ❌ 잘못된 예 - Throwable 파라미터 누락
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
public Response doSomething(Request request) { ... }

private Response fallback(Request request) {
    return Response.error();  // 호출되지 않음!
}

// ✅ 올바른 예
private Response fallback(Request request, Throwable throwable) {
    return Response.error(throwable.getMessage());
}
```

### 9.2 ❌ AOP 의존성 누락

```groovy
// ❌ AOP 없이는 @CircuitBreaker 동작 안 함
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    // spring-boot-starter-aop 누락!
}

// ✅ AOP 의존성 필수
dependencies {
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    implementation 'org.springframework.boot:spring-boot-starter-aop'
}
```

### 9.3 ❌ 비즈니스 예외를 실패로 카운트

```yaml
# ❌ 모든 예외를 실패로 기록
resilience4j:
  circuitbreaker:
    instances:
      myService:
        recordExceptions:
          - java.lang.Exception

# ✅ 시스템 예외만 기록
resilience4j:
  circuitbreaker:
    instances:
      myService:
        recordExceptions:
          - java.io.IOException
          - java.net.ConnectException
        ignoreExceptions:
          - com.example.BusinessException
```

### 9.4 ❌ 같은 클래스 내 메서드 호출

```java
// ❌ 같은 클래스 내 호출은 프록시를 거치지 않음
@Service
public class MyService {
    
    public void methodA() {
        methodB();  // Circuit Breaker 적용 안 됨!
    }
    
    @CircuitBreaker(name = "myService")
    public void methodB() { ... }
}

// ✅ 해결책: 다른 빈으로 분리
@Service
public class ServiceA {
    private final ServiceB serviceB;
    
    public void methodA() {
        serviceB.methodB();  // 프록시를 통해 호출
    }
}

@Service
public class ServiceB {
    @CircuitBreaker(name = "myService")
    public void methodB() { ... }
}
```

### 9.5 ❌ minimumNumberOfCalls 이해 부족

```yaml
slidingWindowSize: 10
minimumNumberOfCalls: 5
failureRateThreshold: 50
```

```
// ❌ 잘못된 이해
"4개 중 4개 실패(100%) → OPEN"  // 실제로는 CLOSED 유지!

// ✅ 올바른 이해
호출 1-4: 모두 실패해도 CLOSED 유지 (4 < minimumNumberOfCalls)
호출 5: 5개째에서 비로소 실패율 계산 시작
        5개 중 5개 실패 = 100% > 50% → OPEN
```

### 9.6 ❌ 테스트에서 상태 초기화 누락

```java
// ❌ 테스트 간 상태가 공유됨
@Test
void test1() {
    // Circuit이 OPEN으로 변경됨
}

@Test
void test2() {
    // 이전 테스트의 OPEN 상태가 유지됨 → 실패!
}

// ✅ 매 테스트 전 초기화
@BeforeEach
void setUp() {
    circuitBreaker.reset();
}
```

---

## 10. Best Practices

### 10.1 서비스별 개별 Circuit Breaker

```yaml
# ✅ 서비스 특성에 맞는 개별 설정
resilience4j:
  circuitbreaker:
    instances:
      paymentService:       # 결제 - 매우 민감
        failureRateThreshold: 20
        waitDurationInOpenState: 120s
      
      inventoryService:     # 재고 - 중간
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
      
      notificationService:  # 알림 - 관대
        failureRateThreshold: 80
        waitDurationInOpenState: 10s
```

### 10.2 적절한 임계값 설정

| 서비스 유형 | failureRate | waitDuration | 이유 |
|------------|-------------|--------------|------|
| 결제/금융 | 20-30% | 60-120s | 높은 신뢰성 필요 |
| 주문/재고 | 40-50% | 30-60s | 비즈니스 크리티컬 |
| 검색/추천 | 60-70% | 10-30s | 캐시로 대체 가능 |
| 알림/로그 | 70-80% | 5-15s | 실패해도 치명적이지 않음 |

### 10.3 이벤트 리스너로 모니터링

```java
@Configuration
public class CircuitBreakerConfig {
    
    @PostConstruct
    public void registerEventListeners() {
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> {
                log.warn("Circuit Breaker 상태 변경: {} → {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState());
                
                // OPEN 전환 시 알림
                if (event.getStateTransition().getToState() == State.OPEN) {
                    alertService.sendUrgent(
                        "Circuit Breaker OPEN: " + circuitBreaker.getName());
                }
            })
            .onFailureRateExceeded(event -> {
                log.warn("실패율 초과: {}%", event.getFailureRate());
            });
    }
}
```

### 10.4 Actuator 엔드포인트 활용

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,circuitbreakers,circuitbreakerevents
  health:
    circuitbreakers:
      enabled: true
```

**조회 API:**

```bash
# 상태 조회
curl http://localhost:8080/actuator/circuitbreakers

# 이벤트 스트림
curl http://localhost:8080/actuator/circuitbreakerevents

# 특정 Circuit Breaker 이벤트
curl http://localhost:8080/actuator/circuitbreakerevents/paymentService
```

### 10.5 프로덕션 체크리스트

- [ ] 각 외부 서비스별 개별 Circuit Breaker 설정
- [ ] 비즈니스 예외 `ignoreExceptions` 등록
- [ ] 모든 Circuit Breaker에 Fallback 메서드 구현
- [ ] Fallback에서 대기열 저장 또는 캐시 반환 로직
- [ ] Actuator 엔드포인트 활성화
- [ ] 상태 전환 알림 설정 (Slack, PagerDuty 등)
- [ ] Grafana 대시보드 구성
- [ ] 부하 테스트 수행
- [ ] 장애 시나리오 훈련 (Chaos Engineering)

---

## 11. 모니터링과 운영

### 11.1 주요 메트릭

| 메트릭 | 설명 | 주의 수준 |
|-------|------|----------|
| `state` | 현재 상태 | OPEN이면 긴급! |
| `failureRate` | 실패율 (%) | 30% 이상 주의 |
| `slowCallRate` | 느린 호출 비율 (%) | 50% 이상 주의 |
| `bufferedCalls` | 윈도우 내 호출 수 | - |
| `notPermittedCalls` | 차단된 호출 수 | 급증 시 주의 |

### 11.2 Prometheus 메트릭

```
# Circuit Breaker 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state{name="paymentService"}

# 실패율
resilience4j_circuitbreaker_failure_rate{name="paymentService"}

# 호출 수
resilience4j_circuitbreaker_calls_total{name="paymentService",kind="successful"}
resilience4j_circuitbreaker_calls_total{name="paymentService",kind="failed"}
```

### 11.3 Grafana 대시보드 예시

```
┌─────────────────────────────────────────────────────────┐
│ Circuit Breaker: paymentService                         │
├─────────────┬─────────────┬─────────────┬──────────────┤
│   상태      │   실패율    │  느린호출율  │   차단 수    │
│  CLOSED ✓  │    12%      │     5%      │     0        │
├─────────────┴─────────────┴─────────────┴──────────────┤
│ [===========|=========================================] │
│ 12%         50% 임계값                                   │
├────────────────────────────────────────────────────────┤
│ 시간별 호출 그래프                                       │
│ ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁                                       │
└────────────────────────────────────────────────────────┘
```

### 11.4 알림 설정 권장

| 이벤트 | 우선순위 | 알림 채널 | 조치 |
|-------|---------|----------|------|
| OPEN 전환 | 🔴 긴급 | PagerDuty + Slack | 즉시 대응 |
| 실패율 70% 초과 | 🟠 높음 | Slack | 모니터링 강화 |
| 느린호출 50% 초과 | 🟡 중간 | Slack | 원인 분석 |
| CLOSED 복귀 | 🟢 정보 | Slack | 확인 |

---

## 12. 실습 가이드

### 12.1 실행 방법

```bash
# 1. 프로젝트 디렉토리로 이동
cd circuit-breaker-demo

# 2. 애플리케이션 실행
./gradlew bootRun

# 3. 다른 터미널에서 테스트
```

### 12.2 테스트 시나리오

#### 시나리오 1: 정상 결제

```bash
# 결제 요청
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amount":10000,"paymentMethod":"CARD"}'

# 예상 응답
{
  "transactionId": "TXN-A1B2C3D4",
  "status": "COMPLETED",
  "source": "PRIMARY"
}
```

#### 시나리오 2: Circuit OPEN 전환

```bash
# 1. 강제 실패 모드 활성화
curl -X POST "http://localhost:8080/api/payments/simulate/force-failure?enabled=true"

# 2. 여러 번 결제 요청 (실패 누적)
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/payments \
    -H "Content-Type: application/json" \
    -d "{\"orderId\":\"ORD-$i\",\"amount\":10000,\"paymentMethod\":\"CARD\"}"
  echo ""
done

# 3. 상태 확인
curl http://localhost:8080/api/payments/circuit-breaker/status

# 예상: state=OPEN
```

#### 시나리오 3: Fallback 확인

```bash
# Circuit OPEN 상태에서 요청
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-100","amount":10000,"paymentMethod":"CARD"}'

# 예상 응답 (Fallback)
{
  "transactionId": "PENDING-1234567890",
  "status": "PENDING",
  "message": "결제 시스템이 일시적으로 과부하 상태입니다.",
  "source": "FALLBACK_CIRCUIT_OPEN"
}
```

#### 시나리오 4: 복구 확인

```bash
# 1. 강제 실패 모드 해제
curl -X POST "http://localhost:8080/api/payments/simulate/force-failure?enabled=false"

# 2. 30초 대기 (waitDurationInOpenState)

# 3. 결제 요청 (HALF_OPEN에서 테스트)
curl -X POST http://localhost:8080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-RECOVER","amount":10000,"paymentMethod":"CARD"}'

# 4. 상태 확인
curl http://localhost:8080/api/payments/circuit-breaker/status

# 예상: state=CLOSED (복구됨)
```

#### 시나리오 5: 초기화

```bash
# 모든 설정 초기화
curl -X POST http://localhost:8080/api/payments/simulate/reset
```

### 12.3 Actuator로 상세 모니터링

```bash
# Circuit Breaker 전체 상태
curl http://localhost:8080/actuator/circuitbreakers

# 특정 Circuit Breaker 이벤트 스트림
curl http://localhost:8080/actuator/circuitbreakerevents/paymentService

# 헬스 체크 (Circuit Breaker 포함)
curl http://localhost:8080/actuator/health
```

---

## 정리

### Circuit Breaker 핵심 요약

| 항목 | 내용 |
|------|------|
| **목적** | 장애 전파 차단, 빠른 실패로 시스템 보호 |
| **상태** | CLOSED → OPEN → HALF_OPEN → CLOSED |
| **트리거** | 실패율 또는 느린 호출 비율이 임계값 초과 |
| **Fallback** | OPEN 상태에서 대체 응답 제공 |
| **라이브러리** | Resilience4j (Spring Boot 4.x 공식) |

### 상태 전환 조건 정리

| 전환 | 조건 |
|------|------|
| CLOSED → OPEN | 실패율 > failureRateThreshold |
| OPEN → HALF_OPEN | waitDurationInOpenState 경과 |
| HALF_OPEN → CLOSED | 테스트 요청의 실패율 ≤ failureRateThreshold |
| HALF_OPEN → OPEN | 테스트 요청의 실패율 > failureRateThreshold |

> **⚠️ 주의**: HALF_OPEN에서 CLOSED로 전환되려면 모든 요청이 성공할 필요는 없습니다.
> `permittedNumberOfCallsInHalfOpenState`개 요청의 **실패율이 임계값 이하**면 CLOSED로 전환됩니다.

### 핵심 설정 파라미터

```yaml
resilience4j:
  circuitbreaker:
    instances:
      myService:
        slidingWindowSize: 10           # 샘플 크기
        minimumNumberOfCalls: 5         # 최소 호출 수
        failureRateThreshold: 50        # 실패율 임계값 (%)
        waitDurationInOpenState: 30s    # OPEN 유지 시간
        permittedNumberOfCallsInHalfOpenState: 3  # HALF_OPEN 허용 수
        recordExceptions:               # 실패로 기록할 예외
          - java.io.IOException
        ignoreExceptions:               # 무시할 예외
          - BusinessException
```

### 다음 학습: Retry 패턴

Circuit Breaker가 장애를 감지하고 차단한다면, **Retry 패턴**은 일시적인 오류를 자동으로 복구합니다. 두 패턴을 함께 사용하면 더 강력한 회복탄력성을 구현할 수 있습니다.

---

*작성일: 2025년*
*Spring Boot 4.x / Spring Framework 7.x 기준*
