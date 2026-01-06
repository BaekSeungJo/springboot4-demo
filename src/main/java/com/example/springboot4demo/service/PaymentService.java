package com.example.springboot4demo.service;

import com.example.springboot4demo.client.PaymentGatewayClient;
import com.example.springboot4demo.exception.BusinessException;
import com.example.springboot4demo.exception.ServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.example.springboot4demo.dto.PaymentDto.PaymentRequest;
import static com.example.springboot4demo.dto.PaymentDto.PaymentResponse;

/**
 * 결제 서비스 - Circuit Breaker 패턴 적용
 * <p>
 * ===========================================================================================
 * Circuit Breaker 핵심 개념
 * ===========================================================================================
 * <p>
 * 1. 목적: 외부 서비스 장애 시 빠른 실패(Fail-Fast)로 시스템 보호
 * <p>
 * 2. 동작 방식
 * - CLOSED: 정상 상태, 모든 요청 통과
 * - OPEN: 장애 감지, 모든 요청 즉시 실패 (Fallback 호출)
 * - HALF_OPEN: 복구 테스트, 일부 요청만 허용
 * <p>
 * 3. 장점:
 * - 스레드 풀 고갈 방지
 * - 연쇄 장애 차단
 * - 장애 서비스에 추가 부하 방지
 * - 빠른 응답 (Fallback)
 */
@Slf4j
@Service
public class PaymentService {
    private final PaymentGatewayClient paymentGatewayClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public PaymentService(
            PaymentGatewayClient paymentGatewayClient,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    // =======================================================================================
    // 방법 1: 어노테이션 기반 Circuit Breaker (권장)
    // =======================================================================================

    /**
     * 결제 처리 - 어노테이션 기반 Circuit Breaker
     *
     * @CircuitBreaker 어노테이션 속성:
     * - name: application.yml에 정의된 인스턴스 이름 (필수)
     * - fallbackMethod: 실패 시 호출할 메서드 이름 (선택, 권장)
     * <p>
     * =======================================================================================
     * Fallback 메서드 규칙 (중요!)
     * =======================================================================================
     * <p>
     * 1. 메서드 시그니처:
     * - 원본 메서드와 동일한 파라미터
     * - + 마지막에 Throwable (또는 특정 예외) 파라미터 추가
     * - 반환 타입은 원본과 동일
     * <p>
     * 2. 접근 제어자:
     * - private, protected, public 모두 가능
     * - 같은 클래스 또는 상위 클래스에 정의
     * <p>
     * 3. 예외 타입별 Fallback:
     * - 여러 Fallback 메서드를 오버로딩하여 예외별 처리 가능
     * - 가장 구체적인 예외 타입 매칭
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "processPaymentFallback")
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("=== 결체 처리 시작 ===");
        log.info("Circuit Breaker 상태: {}", getCircuitBreakerState("paymentService"));
        log.info("주문 ID: {}, 금액: {}", request.orderId(), request.amount());

        // 실제 결제 게이트웨이 호출
        // 이 호출이 실패하면 Circuit Breaker가 감지하고 기록
        PaymentResponse response = paymentGatewayClient.processPayment(request);

        log.info("결제 완료: transactionId={}", response.transactionId());
        return response;
    }

    /**
     * Fallback 메서드 - Circuit Breaker OPEN 또는 예외 발생 시 호출
     * <p>
     * =======================================================================================
     * Fallback 전략 가이드
     * =======================================================================================
     * <p>
     * 1. 대기열 추가 (권장)
     * - 결제를 PENDING 상태로 저장
     * - 배치/스케줄러가 나중에 재처리
     * - 사용자에게 안내 메시지 제공
     * <p>
     * 2. 캐시된 응답 반환
     * - 읽기 전용 API에 적합
     * - 결제에는 부적합
     * <p>
     * 3. 대체 서비스 호출
     * - 백업 PG사 사용
     * - 추가 비용/복잡성 고려
     * <p>
     * 4. 기본값 반환
     * - 가장 단순하지만 주의 필요
     * - 결제에는 부적합
     */
    private PaymentResponse processPaymentFallback(
            PaymentRequest request,
            Throwable throwable
    ) {
        log.warn("=== Fallback 실행 ===");
        log.warn("예외 타입: {}", throwable.getClass().getSimpleName());
        log.warn("예외 메시지: {}", throwable.getMessage());

        // 예외 타입에 따른 분기 처리
        if (throwable instanceof CallNotPermittedException) {
            // Circuit Breaker가 OPEN 상태
            log.warn("Circuit Breaker Open - 요청 차단됨");

            // 실무에서는 여기서 대기열에 저장
            saveToPaymentQueue(request, "CIRCUIT_OPEN");

            return PaymentResponse.circuitOpen(request.orderId(), request.amount());
        }

        if (throwable instanceof TimeoutException) {
            log.warn("타임아웃 발생");
            saveToPaymentQueue(request, "TIMEOUT");
            return PaymentResponse.timeout(request.orderId(), request.amount());
        }

        if (throwable instanceof ServiceException serviceException) {
            log.warn("서비스 예외: {}", serviceException.errorCode());
            saveToPaymentQueue(request, serviceException.errorCode());
            return PaymentResponse.serviceError(
                    request.orderId(),
                    request.amount(),
                    serviceException.getMessage()
            );
        }

        // 기타 예외
        log.error("예상치 못한 오류", throwable);
        saveToPaymentQueue(request, "UNKNOWN_ERROR");
        return PaymentResponse.serviceError(
                request.orderId(),
                request.amount(),
                "알 수 없는 오류가 발생했습니다"
        );
    }

    /**
     * 비즈니스 예외 전용 Fallback (오버로딩)
     * <p>
     * BusinessException은 ignoreExceptions 에 등록되어 있으므로
     * Circuit Breaker가 실패로 카운트하지 않습니다.
     * 하지만 예외 자체는 발생하므로 Fallback이 호출됩니다.
     * <p>
     * 참고: 이 경우 비즈니스 예외는 그대로 throw하는 것이 좋습니다.
     */
    private PaymentResponse processPaymentFallback(
            PaymentRequest request,
            BusinessException exception
    ) {
        log.info("비즈니스 예외 Fallback: {}", exception.errorCode());

        // 비즈니스 예외는 그대로 전파 (사용자에게 정확한 오류 전달)
        throw exception;
    }

    /**
     * 대기열 저장 (시뮬레이션)
     * 실무에서는 Redis, Kafka, RabbitMQ 등에 저장
     */
    private void saveToPaymentQueue(
            PaymentRequest request,
            String reason
    ) {
        log.info("결제 대기열 저장: orderId={}, reason={}", request.orderId(), reason);
        // TODO: 실제 대기열 저장 로직
        // messageQueue.send(new PaymentQueueMessage(request, reason));
    }

    // =======================================================================================
    // 방법 2: 프로그래밍 방식 Circuit Breaker
    // =======================================================================================

    /**
     * 프로그래밍 방식의 Circuit Breaker 사용
     * <p>
     * 언제 사용하는가?
     * - 동적으로 Circuit Breaker 설정을 변경해야 할 때
     * - 조건부로 Circuit Breaker를 적용해야 할 때
     * - 더 세밀한 제어가 필요할 때
     * - 테스트 코드에서 명시적으로 제어할 대
     */
    public PaymentResponse processPaymentProgrammatic(PaymentRequest request) {
        // Registry에서 Circuit Breaker 인스턴스 가져오기
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("paymentService");

        log.info("프로그래밍 방식 Circuit Breaker");
        log.info("현재 상태: {}", cb.getState());
        log.info("실패율: {}%", cb.getMetrics().getFailureRate());

        // executeSupplier: 람다를 실행하고 Circuit Breaker로 감싸기
        try {
            return cb.executeSupplier(() -> {
                log.info("결제 처리 시작 (프로그래밍 방식)");
                return paymentGatewayClient.processPayment(request);
            });
        } catch (CallNotPermittedException e) {
            // Circuit Breaker가 OPEN 상태
            log.warn("Circuit Breaker OPEN - 수동 Fallback 실행");
            return PaymentResponse.circuitOpen(request.orderId(), request.amount());
        } catch (Exception e) {
            log.error("결제 실패", e);
            return PaymentResponse.serviceError(request.orderId(), request.amount(), e.getMessage());
        }
    }

    /**
     * 함수형 스타일 Circuit Breaker
     */
    public PaymentResponse processPaymentFunctional(PaymentRequest request) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                circuitBreakerRegistry.circuitBreaker("paymentService");

        // 함수형 Circuit Breaker로 데코레이트
        Supplier<PaymentResponse> decorateSupplier =
                io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb, () ->
                        paymentGatewayClient.processPayment(request));

        try {
            return decorateSupplier.get();
        } catch (Exception e) {
            return PaymentResponse.serviceError(request.orderId(), request.amount(), e.getMessage());
        }
    }

    // =======================================================================================
    // 모니터링 및 관리 메서드
    // =======================================================================================

    /**
     * Circuit Breaker 상태 조회
     */
    public CircuitBreakerStatus getCircuitBreakerStatus(String name) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                circuitBreakerRegistry.circuitBreaker(name);

        io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics metrics =
                cb.getMetrics();

        return new CircuitBreakerStatus(
                cb.getName(),
                cb.getState().name(),
                metrics.getFailureRate(),
                metrics.getSlowCallRate(),
                metrics.getNumberOfSuccessfulCalls(),
                metrics.getNumberOfFailedCalls(),
                metrics.getNumberOfSlowCalls(),
                metrics.getNumberOfNotPermittedCalls(),
                metrics.getNumberOfBufferedCalls()
        );
    }

    /**
     * Circuit Breaker 상태 문자열 조회
     */
    private String getCircuitBreakerState(String name) {
        return circuitBreakerRegistry.circuitBreaker(name).getState().name();
    }

    /**
     * Circuit Breaker 강제 상태 전환 (테스트/관리용)
     */
    public void transitionCircuitBreakerState(
            String name,
            String targetState
    ) {
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);

        switch (targetState.toUpperCase()) {
            case "OPEN" -> {
                cb.transitionToOpenState();
                log.warn("Circuit Breaker {} 강제 OPEN", name);
            }
            case "CLOSED" -> {
                cb.transitionToClosedState();
                log.info("Circuit Breaker {} 강제 CLOSED", name);
            }
            case "HALF_OPEN" -> {
                cb.transitionToHalfOpenState();
                log.info("Circuit Breaker {} 강제 HALF_OPEN", name);
            }
            case "REST" -> {
                cb.reset();
                log.info("Circuit Breaker {} 리셋", name);
            }
            default -> throw new IllegalArgumentException(
                    "유효하지 않는 상태: " + targetState);
        }
    }

    /**
     * Circuit Breaker 상태 DTO
     */
    public record CircuitBreakerStatus(
            String name,
            String state,
            float failureRate,
            float slowCallRate,
            int successfulCalls,
            int failedCalls,
            int slowCalls,
            long notPermittedCalls,
            int bufferedCalls
    ) {}

}
