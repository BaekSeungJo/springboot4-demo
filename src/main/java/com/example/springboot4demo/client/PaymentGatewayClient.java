package com.example.springboot4demo.client;

import com.example.springboot4demo.dto.PaymentDto.PaymentRequest;
import com.example.springboot4demo.dto.PaymentDto.PaymentResponse;
import com.example.springboot4demo.exception.BusinessException;
import com.example.springboot4demo.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 외부 결제 게이트웨이 클라이언트 (시뮬레이션)
 * <p>
 * 실제 프로덕션에서는 RestClient 또는 WebClient 사용하여
 * 외부 PG사 API를 호출합니다.
 * <p>
 * 이 클래스는 Circuit Breaker 동작을 테스트하기 위해
 * 다양한 장애 상황을 시뮬레이션합니다.
 */
@Slf4j
@Component
public class PaymentGatewayClient {

    // 호출 추적 (테스트/모니터링용)
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger successCalls = new AtomicInteger(0);
    private final AtomicInteger failedCalls = new AtomicInteger(0);

    // 장애 시뮬레이션 설정
    private volatile boolean forceFailure = false;      // 강제 실패
    private volatile long simulatedDelayMs = 0;        // 지연 시간
    private volatile int failEveryNthCall = 0;          // N번째 호출마다 실패
    private volatile double failureProbability = 0.0;   // 랜덤 실패 확률 (0.0 ~ 1.0)


    /**
     * 결제 처리 요청
     *
     * @param request 결제 요청
     * @return 결제 응답
     * @throws ServiceException  시스템 장애 시
     * @throws BusinessException 비즈니스 규칙 위반 시
     */
    public PaymentResponse processPayment(PaymentRequest request) {
        int callNumber = totalCalls.incrementAndGet();
        log.info("결제 요청 #{}: orderId={}, amount={}", callNumber, request.orderId(), request.amount());

        try {
            // 1. 지연 시뮬레이션
            simulateDelay();

            // 2. 장애 시뮬레이션
            simulateFailure(callNumber);

            // 3. 비즈니스 검증 (잔액 부족 등)
            validateBusinessRules(request);

            // 4. 정상 처리
            String transactionId = generateTransactionId();
            successCalls.incrementAndGet();

            log.info("결제 성공 #{}: transactionId={}", callNumber, transactionId);
            return PaymentResponse.success(transactionId, request.orderId(), request.amount());
        } catch (ServiceException | BusinessException e) {
            failedCalls.incrementAndGet();
            throw e;
        } catch (Exception e) {
            failedCalls.incrementAndGet();
            throw new ServiceException("PaymentGateway", "예상치 못한 오류", e);
        }
    }

    /**
     * 지연 시뮬레이션
     */
    private void simulateDelay() {
        if (simulatedDelayMs > 0) {
            log.warn("지연 시뮬레이션: {}ms", simulatedDelayMs);
            try {
                Thread.sleep(simulatedDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("PaymentGateway", "요청 인터럽트됨");
            }
        }
    }

    /**
     * 장애 시뮬레이션
     */
    private void simulateFailure(int callNumber) {
        // 강제 실패 모드
        if (forceFailure) {
            log.error("강제 실패 모드 활성화");
            throw new ServiceException("PaymentGateway", "CONNECTION_REFUSED", "PG사 서버에 연결할 수 없습니다 (시뮬레이션)");
        }

        // N번째 호출마다 실패
        if (failEveryNthCall > 0 && callNumber % failEveryNthCall == 0) {
            log.error("N번째 호출 실패 시뮬레이션: {} / {}", callNumber, failEveryNthCall);
            throw new ServiceException("PaymentGateway", "INTERMITTENT_ERROR", "일시적인 서버 오류 (시뮬레이션)");
        }

        // 랜덤 실패
        if (failureProbability > 0 && Math.random() < failureProbability) {
            log.error("랜덤 실패 시뮬레이션: 확률 {}%", failureProbability * 100);
            throw new ServiceException("PaymentGateway", "RANDOM_ERROR", "랜덤 서버 오류 (시뮬레이션)");
        }
    }

    /**
     * 비즈니스 규칙 검증
     * <p>
     * 이 예외는 Circuit Breaker가 무시합니다 (실패로 카운트하지 않음)
     */
    private void validateBusinessRules(PaymentRequest request) {
        // 예시: 특정 금액 이상은 추가 인증 필요
        if (request.amount().compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
            throw new BusinessException("AMOUNT_LIMIT_EXCEEDED", "1천만원 이상 결제는 추가 인증이 필요합니다");
        }

        // 예시: 테스트 주문 ID느 거부
        if (request.orderId().startsWith("TEST-REJECT")) {
            throw new BusinessException("INVALID_ORDER", "유효하지 않은 주문입니다: " + request.orderId());
        }
    }

    private String generateTransactionId() {
        return "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ===========================================================================================
    // 장애 시뮬레이션 제어 메서드 (테스트/관리용)
    // ===========================================================================================

    /**
     * 강제 실패 모드 설정
     */
    public void setForceFailure(boolean forceFailure) {
        this.forceFailure = forceFailure;
        log.warn("강제 실패 모드: {}", forceFailure ? "ON" : "OFF");
    }

    /**
     * 지연 시간 설정 (밀리초)
     */
    public void setSimulatedDelayMs(long delayMs) {
        this.simulatedDelayMs = delayMs;
        log.warn("지연 시간 설정: {}ms", delayMs);
    }

    /**
     * N번째 호출마다 실패 설정
     */
    public void setFailEveryNthCall(int n) {
        this.failEveryNthCall = n;
        log.warn("N번째 호출 실패 설정: {}", n);
    }

    /**
     * 랜덤 실패 확률 설정 (0.0 ~ 1.0)
     */
    public void setFailureProbability(double probability) {
        this.failureProbability = Math.max(0.0, Math.min(1.0, probability));
        log.warn("랜덤 실패 확률 설정: {}%", this.failureProbability * 100);
    }

    /**
     * 모든 설정 초기화
     */
    public void reset() {
        this.forceFailure = false;
        this.simulatedDelayMs = 0;
        this.failEveryNthCall = 0;
        this.failureProbability = 0.0;
        this.totalCalls.set(0);
        this.successCalls.set(0);
        this.failedCalls.set(0);
        log.info("PaymentGatewayClient 초기화 완료");
    }

    /**
     * 통계 조회
     */
    public ClientStats getStats() {
        return new ClientStats(
                totalCalls.get(),
                successCalls.get(),
                failedCalls.get(),
                forceFailure,
                simulatedDelayMs,
                failEveryNthCall,
                failureProbability
        );
    }

    public record ClientStats(
            int totalCalls,
            int successCalls,
            int failedCalls,
            boolean forceFailure,
            long simulatedDelayMs,
            int failEveryNthCall,
            double failureProbability
    ) {}
}
