package com.example.springboot4demo.controller;

import com.example.springboot4demo.client.PaymentGatewayClient;
import com.example.springboot4demo.dto.PaymentDto;
import com.example.springboot4demo.dto.PaymentDto.PaymentRequest;
import com.example.springboot4demo.dto.PaymentDto.PaymentResponse;
import com.example.springboot4demo.service.PaymentService;
import com.example.springboot4demo.service.PaymentService.CircuitBreakerStatus;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Circuit Breaker 테스트 API
 * <p>
 * 이 컨트롤러는 Circuit Breaker 동작을 테스트하기 위한
 * 다양한 엔드포인트를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final PaymentGatewayClient paymentGatewayClient;

    public PaymentController(
            PaymentService paymentService,
            PaymentGatewayClient paymentGatewayClient
    ) {
        this.paymentService = paymentService;
        this.paymentGatewayClient = paymentGatewayClient;
    }

    // =======================================================================================
    // 결제 API
    // =======================================================================================

    /**
     * 결제 처리 (어노테이션 기반 Circuit Breaker)
     * <p>
     * 사용법:
     * curl -X POST http://localhost:8080/api/payments
     * -H "Content-Type: application/json"
     * -d '{"orderId":"ORD-001", "amount":10000, "paymentMethod":"CARD"}'
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @Valid @RequestBody PaymentRequest request
    ) {
        log.info("결제 API 호출: orderId={}", request.orderId());
        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 결체 처리 (프로그래밍 방식)
     */
    @PostMapping("/programmatic")
    public ResponseEntity<PaymentResponse> processPaymentProgrammatic(
            @Valid @RequestBody
            PaymentRequest request
    ) {
        PaymentResponse response = paymentService.processPaymentProgrammatic(request);
        return ResponseEntity.ok(response);
    }

    // =======================================================================================
    // Circuit Breaker 모니터링
    // =======================================================================================

    /**
     * Circuit Breaker 상태 조회
     * <p>
     * curl http://localhost:8080/api/payments/circuit-breaker/status
     */
    @GetMapping("/circuit-breaker/status")
    public ResponseEntity<CircuitBreakerStatus> getCircuitBreakerStatus() {
        return ResponseEntity.ok(paymentService.getCircuitBreakerStatus("paymentService"));
    }

    /**
     * Circuit Breaker 상태 강제 전환 (관리용)
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/circuit-breaker/state?target=OPEN"
     * curl -X POST "http://localhost:8080/api/payments/circuit-breaker/state?target=CLOSED"
     * curl -X POST "http://localhost:8080/api/payments/circuit-breaker/state?target==RESET"
     */
    @PostMapping("/circuit-breaker/state")
    public ResponseEntity<Map<String, String>> transitionState(
            @RequestParam String target
    ) {
        paymentService.transitionCircuitBreakerState("paymentService", target);

        return ResponseEntity.ok(Map.of(
                "message", "상태 전환 완료",
                "newState", target
        ));
    }

    // =======================================================================================
    // 장애 시뮬레이션 제어
    // =======================================================================================

    /**
     * 강제 실패 모드 설정
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/simulate/force-failure?enabled=true"
     */
    @PostMapping("/simulate/force-failure")
    public ResponseEntity<Map<String, Object>> setForceFailure(
            @RequestParam boolean enabled
    ) {
        paymentGatewayClient.setForceFailure(enabled);

        return ResponseEntity.ok(Map.of(
                "message", "강제 실패 모드 " + (enabled ? "활성화" : "비활성화"),
                "forceFailure", enabled
        ));
    }

    /**
     * 지연 시간 설정
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/simulate/delay?ms=5000"
     */
    @PostMapping("/simulate/delay")
    public ResponseEntity<Map<String, Object>> setDelay(
            @RequestParam long ms
    ) {
        paymentGatewayClient.setSimulatedDelayMs(ms);

        return ResponseEntity.ok(Map.of(
                "message", "지연 시간 설정: " + ms + "ms",
                "delayMs", ms
        ));
    }

    /**
     * N버째 호출 실패 설정
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/simulate/fail-every?n=3"
     */
    @PostMapping("/simulate/fail-every")
    public ResponseEntity<Map<String, Object>> setFailEvery(
            @RequestParam int n
    ) {
        paymentGatewayClient.setFailEveryNthCall(n);

        return ResponseEntity.ok(Map.of(
                "message", n + "번째 호출마다 실패",
                "failEveryNthCall", n
        ));
    }

    /**
     * 랜덤 실패 확률 설정
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/simulate/failure-rate?probability=0.5"
     */
    @PostMapping("/simulate/failure-rate")
    public ResponseEntity<Map<String, Object>> setFailureProbability(
            @RequestParam double probability
    ) {
        paymentGatewayClient.setFailureProbability(probability);

        return ResponseEntity.ok(Map.of(
                "message", "실패 확률: " + (probability * 100) + "%",
                "failureProbability", probability
        ));
    }

    /**
     * 시뮬레이션 설정 초기화
     * <p>
     * curl -X POST "http://localhost:8080/api/payments/simulate/reset"
     */
    @PostMapping("/simulate/reset")
    public ResponseEntity<Map<String, String>> reset() {
        paymentGatewayClient.reset();
        paymentService.transitionCircuitBreakerState("paymentService", "RESET");

        return ResponseEntity.ok(Map.of(
                "message", "모든 설정 초기화 완료"
        ));
    }

    /**
     * 현재 시뮬레이션 설정 조회
     * <p>
     * curl http://localhost:8080/api/payments/simulate/stats
     */
    @GetMapping("/simulate/stats")
    public ResponseEntity<PaymentGatewayClient.ClientStats> getStats() {
        return ResponseEntity.ok(paymentGatewayClient.getStats());
    }

    // =======================================================================================
    // 부하 테스트
    // =======================================================================================

    /**
     * 동시 요청 부하 테스트
     * <p>
     * curl "http://localhost:8080/api/payments/load-test?count=20"
     */
    @GetMapping("/load-test")
    public ResponseEntity<LoadTestResult> loadTest(
            @RequestParam(defaultValue = "10") int count
    ) {
        log.info("부하 테스트 시작: {} 요청", count);
        long startTime = System.currentTimeMillis();

        // 동시에 여러 요청 발송
        List<CompletableFuture<PaymentResponse>> futures = IntStream.range(0, count)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    PaymentRequest request = new PaymentRequest(
                            "LOAD-TEST-" + i,
                            BigDecimal.valueOf(1000 + i * 100),
                            "CARD",
                            null
                    );
                    return paymentService.processPayment(request);
                }))
                .toList();

        // 모든 요청 완료 대기
        List<PaymentResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 결과 집계
        long successCount = responses.stream()
                .filter(r -> r.source() == PaymentDto.ResponseSource.PRIMARY)
                .count();

        long fallbackCount = responses.stream()
                .filter(r -> r.source() != PaymentDto.ResponseSource.PRIMARY)
                .count();

        long duration = System.currentTimeMillis() - startTime;

        CircuitBreakerStatus cbStatus = paymentService.getCircuitBreakerStatus("paymentService");

        return ResponseEntity.ok(new LoadTestResult(
                count,
                (int) successCount,
                (int) fallbackCount,
                duration,
                cbStatus
        ));
    }

    /**
     * 순차 요청 테스트 (Circuit Breaker 동작 관찰용)
     * <p>
     * curl "http://localhost:8080/api/payments/sequential-test?count=15"
     */
    @GetMapping("/sequential-test")
    public ResponseEntity<List<TestResult>> sequentialTest(
            @RequestParam(defaultValue = "15") int count
    ) {
        log.info("순차 테스트 시작: {} 요청", count);
        List<TestResult> results = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            PaymentRequest request = new PaymentRequest(
                    "SEQ-TEST-" + i,
                    BigDecimal.valueOf(1000),
                    "CARD",
                    null
            );

            CircuitBreakerStatus beforeStatus = paymentService.getCircuitBreakerStatus("paymentService");

            PaymentResponse response = paymentService.processPayment(request);

            CircuitBreakerStatus afterStatus = paymentService.getCircuitBreakerStatus("paymentService");

            results.add(new TestResult(
                    i + 1,
                    request.orderId(),
                    response.status().name(),
                    response.source().name(),
                    beforeStatus.state(),
                    afterStatus.state(),
                    afterStatus.failureRate()
            ));

            log.info("테스트 #{}: {} -> {} (실패율: {}%)",
                    (i + 1), beforeStatus.state(), afterStatus.state(), afterStatus.failureRate());
        }

        return ResponseEntity.ok(results);
    }


    // =======================================================================================
    // DTO
    // =======================================================================================
    public record LoadTestResult(
            int totalRequests,
            int successCount,
            int fallbackCount,
            long durationMs,
            CircuitBreakerStatus circuitBreakerStatus
    ) {}

    public record TestResult(
            int requestNumber,
            String orderId,
            String paymentStatus,
            String responseSource,
            String cbStateBefore,
            String cbStateAfter,
            float failureRate
    ) {}
}
