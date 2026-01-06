package com.example.springboot4demo.service;

import com.example.springboot4demo.client.PaymentGatewayClient;
import com.example.springboot4demo.dto.PaymentDto;
import com.example.springboot4demo.dto.PaymentDto.PaymentRequest;
import com.example.springboot4demo.dto.PaymentDto.PaymentResponse;
import com.example.springboot4demo.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Circuit Breaker 상세 통합 테스트
 * <p>
 * 테스트 시나리오
 * 1. CLOSED 상태에서 정상 처리
 * 2. 실패율 초과 시 OPEN 상태 전환
 * 3. OPEN 상태에서 Fallback 응답
 * 4. waitDuration 후 HALF_OPEN 전환
 * 5. HALF_OPEN에서 성공 시 CLOSED 복귀
 * 6. HALF_OPEN에서 실패 시 다시 OPEN
 * 7. 느린 호출 감지
 * 8. 비즈니스 예외는 실패로 카운트되지 않음
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CircuitBreakerDetailedTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // 테스트 전 초기화
        paymentGatewayClient.reset();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("paymentService");
        circuitBreaker.reset();
    }

    // ====================================================================================
    // 시나리오 1: CLOSED 상태에서 정상 처리
    // ====================================================================================

    @Test
    @Order(1)
    @DisplayName("CLOSED 상태에서 결제가 정상 처리되어야 한다")
    void testClosedStateSuccessfulPayment() {
        // given: 초기 상태 확인
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);

        PaymentRequest request = createRequest("ORD-001");

        // when
        PaymentResponse response = paymentService.processPayment(request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(PaymentDto.PaymentStatus.COMPLETED);
        assertThat(response.source()).isEqualTo(PaymentDto.ResponseSource.PRIMARY);
        assertThat(response.transactionId()).startsWith("TXN-");

        // Circuit Breaker 상태 확인
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    // ====================================================================================
    // 시나리오 2: 실패율 초과 시 OPEN 상태 전환
    // ====================================================================================
    @Test
    @Order(2)
    @DisplayName("실패율이 임계값을 초과하면 OPEN 상태로 전환되어야 한다.")
    void testTransitionToOpenOnFailureRateExceeded() {
        // given: 강제 실패 모드 활성화
        paymentGatewayClient.setForceFailure(true);

        // 설정 확인: minumulNubmerofCalls=5, failureRateThreshold=50%
        // 하지만 paymentService는 sensitive 설정 상속 (minumulNubmerofCalls=10)
        // 그리고 failureRateThreshold=25로 오버라이드됨

        List<PaymentResponse> responses = new ArrayList<>();

        // when: 충분한 실패 요청 발생
        for (int i = 0; i < 15; i++) {
            PaymentResponse response = paymentService.processPayment(createRequest("FAIL-" + i));
            responses.add(response);

            System.out.printf("호출 #%d: 상태=%s, 실패율=%.1f%%\n",
                    i + 1, circuitBreaker.getState(), circuitBreaker.getMetrics().getFailureRate());
        }

        // then: OPEN 상태로 전환됨
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

        // Fallback 응답 확인
        long fallbackCount = responses.stream()
                .filter(r -> r.source() != PaymentDto.ResponseSource.PRIMARY)
                .count();
        assertThat(fallbackCount).isGreaterThan(0);
    }

    // ====================================================================================
    // 시나리오 3: OPEN 상태에서 Fallback 응답
    // ====================================================================================

    @Test
    @Order(3)
    @DisplayName("OPEN 상태에서 즉시 Fallback이 반환되어야 한다")
    void testOpenStateReturnsImmediateFallback() {
        // given: OPEN 상태로 강제 전환
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

        int initialCallCount = paymentGatewayClient.getStats().totalCalls();
        PaymentRequest request = createRequest("OPEN-TEST");

        // when
        long startTime = System.currentTimeMillis();
        PaymentResponse response = paymentService.processPayment(request);
        long duration = System.currentTimeMillis() - startTime;

        // then
        // 1. Fallback 응답 확인
        assertThat(response.source()).isEqualTo(PaymentDto.ResponseSource.FALLBACK_CIRCUIT_OPEN);
        assertThat(response.status()).isEqualTo(PaymentDto.PaymentStatus.PENDING);
        assertThat(response.message()).contains("과부하");

        // 2. 실제 서비스 호출 없음 확인
        assertThat(paymentGatewayClient.getStats().totalCalls()).isEqualTo(initialCallCount);

        // 3. 빠른 응답 확인 (실제 호출 없으므로 매우 빠름)
        assertThat(duration).isLessThan(100); // 100ms 이내

        // 4. notPermittedCalls 증가 확인
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls())
                .isGreaterThan(0);
    }

    // ====================================================================================
    // 시나리오 4: waitDuration 후 HALF_OPEN 전환
    // ====================================================================================

    @Test
    @Order(4)
    @DisplayName("OPEN 상태에서 waitDuration 후 HALF_OPEN으로 전환되어야 한다")
    void testTransitionToHalfOpenAfterWaitDuration() {
        // given: OPEN 상태로 강제 전환
        circuitBreaker.transitionToOpenState();

        // when: 강제로 HALF_OPEN 전환 (테스트에서는 대기 시간 생략)
        circuitBreaker.transitionToHalfOpenState();

        // then
        assertThat(circuitBreaker.getState()).isEqualTo(State.HALF_OPEN);
    }

    // ====================================================================================
    // 시나리오 5: HALF_OPEN에서 성공 시 CLOSED 복귀
    // ====================================================================================
    @Test
    @Order(5)
    @DisplayName("HALF_OPEN 상태에서 테스트 호출 성공 시 CLOSED로 복귀해야 한다")
    void testTransitionToClosedAfterSuccessInHalfOpen() {
        // given: HALF_OPEN 상태로 전환
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(State.HALF_OPEN);

        // 정상 동작하도록 설정
        paymentGatewayClient.setForceFailure(false);

        // when: persmittedNumberOfCallsInHalfOpenState(5)만큼 성공 요청
        // paymentService 설정: persmittedNumberOfCallsInHalfOpenState가 sensitive 기본값 5 사용
        for (int i = 0; i < 5; i++) {
            PaymentResponse response = paymentService.processPayment(createRequest("HALFOPEN-SUCCESS-" + i));

            System.out.printf("HALF_OPEN 호출 #%d: 상태=%s\n", i + 1, circuitBreaker.getState());
            assertThat(response.source()).isEqualTo(PaymentDto.ResponseSource.PRIMARY);
        }

        // then: CLOSED 상태로 복귀
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }

    // ====================================================================================
    // 시나리오 6: HALF_OPEN에서 실패 시 다시 OPEN
    // ====================================================================================

    @Test
    @Order(6)
    @DisplayName("HALF_OPEN 상태에서 실패 시 다시 OPEN으로 전환되어야 한다.")
    void testTransitionToOpenOnFailureInHalfOpen() {
        // given: HALF_OPEN 상태로 전환
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(State.HALF_OPEN);

        // 강제 실패 모드
        paymentGatewayClient.setForceFailure(true);

        // when: persmittedNumberOfCallsInHalfOpenState(5)만큼 실패 요청
        for (int i = 0; i < 5; i++) {
            PaymentResponse response = paymentService.processPayment(createRequest("HALFOPEN-FAIL-" + i));

            System.out.printf("HALF_OPEN 호출 #%d: 상태=%s\n", i + 1, circuitBreaker.getState());

            assertThat(response.source()).isEqualTo(PaymentDto.ResponseSource.FALLBACK_ERROR);
        }

        // then: 다시 OPEN 상태로
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);
    }

    // ====================================================================================
    // 시나리오 7: 느린 호출 감지
    // ====================================================================================

    @Test
    @Order(7)
    @DisplayName("느린 호출이 임계값을 초과하면 OPEN 상태로 전환되어야 한다")
    void testSlowCallsTriggersOpen() {
        // given: 느린 응답 시뮬레이션 (slowCallDurationThreshold: 1s)
        paymentGatewayClient.setSimulatedDelayMs(2000); // 2초 지연

        // when: 충분한 느린 호출 발생
        for(int i = 0; i < 15; i++) {
            paymentService.processPayment(createRequest("SLOW-" + i));

            System.out.printf("느린 호출 #%d: 상태=%s, 느린호출율=%.1f%%\n",
                    i + 1, circuitBreaker.getState(), circuitBreaker.getMetrics().getSlowCallRate());

            if(circuitBreaker.getState() == State.OPEN) {
                break;
            }
        }

        // then: 느린 호출 비율 확인
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfSlowCalls()).isEqualTo(10);
        assertThat(metrics.getSlowCallRate()).isEqualTo(100);
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);
    }

    // ====================================================================================
    // 시나리오 8: 비즈니스 예외는 실패로 카운트되지 않음
    // ====================================================================================

    @Test
    @Order(8)
    @DisplayName("비즈니스 예외는 Circuit Breaker 실패로 카운트되지 않아야 한다")
    void testBusinessExceptionNotCountedAsFailure() {
        // given: 비즈니스 예외를 발생시키는 요청
        // 10,000,000원 초과 -> AMOUNT_LIMIT_EXCEEDED 비즈니스 예외
        PaymentRequest request = new PaymentRequest(
                "BUSINESS-EXCEPTION-TEST",
                BigDecimal.valueOf(20_000_000), // 2천만원
                "CARD",
                null
        );

        int initialFailedCalls = circuitBreaker.getMetrics().getNumberOfFailedCalls();

        // when: 비즈니스 예외 발생 예상
        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(BusinessException.class);

        // then: 실패 카운트가 증가하지 않음
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(initialFailedCalls);

        // Circuit Breaker는 여전히 CLOSED 상태
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }

    // ====================================================================================
    // 시나리오 9: 메트릭 정확성 검증
    // ====================================================================================

    @Test
    @Order(9)
    @DisplayName("Circuit Breaker 메트릭이 정확히 기록되어야 한다")
    void testMetricsAccuracy() {
        // given
        int successCount = 7;
        int failCount = 3;

        // when: 성공 요청
        IntStream.range(0, successCount).forEach(i ->
                paymentService.processPayment(createRequest("SUCCESS-" + i)));

        // 실패 요청
        paymentGatewayClient.setForceFailure(true);
        IntStream.range(0, failCount).forEach(i ->
                paymentService.processPayment(createRequest("FAIL-" + i)));

        // then
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(successCount);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(failCount);
        assertThat(metrics.getNumberOfBufferedCalls()).isEqualTo(successCount + failCount);

        // 실패율 계산: 3 / 10 = 30%
        float expectedFailureRate = (float) failCount / (successCount + failCount) * 100;
        assertThat(metrics.getFailureRate()).isCloseTo(expectedFailureRate, within(0.1f));
    }

    // ====================================================================================
    // Helper Methods
    // ====================================================================================

    private PaymentRequest createRequest(String orderId) {
        return new PaymentRequest(
                orderId,
                BigDecimal.valueOf(10000),
                "CARD",
                "test@exmaple.com"
        );
    }
}
