package com.example.springboot4demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 관련 DTO
 */
public class PaymentDto {

    /**
     * 결제 요청
     */
    public record PaymentRequest(
            @NotBlank(message = "주문 ID는 필수입니다")
            String orderId,

            @NotNull(message = "결제 금액은 필수입니다")
            @Positive(message = "결제 금액은 양수여야 합니다")
            BigDecimal amount,

            @NotBlank(message = "결제 수단은 필수입니다")
            String paymentMethod,

            String customerEmail
    ) {}

    /**
     * 결제 응답
     */
    public record PaymentResponse(
            String transactionId,
            String orderId,
            BigDecimal amount,
            PaymentStatus status,
            String message,
            Instant processedAt,
            ResponseSource source   // 응답 출처 (정상 or Fallback)
    ) {
        /**
         * 정상 결제 성공 읍답
         */
        public static PaymentResponse success(
                String transactionId,
                String orderId,
                BigDecimal amount) {
            return new PaymentResponse(
                    transactionId,
                    orderId,
                    amount,
                    PaymentStatus.COMPLETED,
                    "결제가 완료되었습니다.",
                    Instant.now(),
                    ResponseSource.PRIMARY
            );
        }

        /**
         * Fallback 응답 - Circuit Breaker OPEN 상태
         */
        public static PaymentResponse circuitOpen(String orderId, BigDecimal amount) {
            return new PaymentResponse(
                    generatePendingId(),
                    orderId,
                    amount,
                    PaymentStatus.PENDING,
                    "결제 시스템이 일시적으로 과부하 상태입니다. 결제가 대기열에 추가되었습니다.",
                    Instant.now(),
                    ResponseSource.FALLBACK_CIRCUIT_OPEN
            );
        }

        /**
         * Fallback 응답 - 서비스 예외 발생
         */
        public static PaymentResponse serviceError(String orderId, BigDecimal amount, String errorMessage) {
            return new PaymentResponse(
                    generatePendingId(),
                    orderId,
                    amount,
                    PaymentStatus.PENDING,
                    "결제 처리 중 오류가 발생했습니다: " + errorMessage,
                    Instant.now(),
                    ResponseSource.FALLBACK_ERROR
            );
        }

        /**
         * Fallback 응답 - 타임아웃
         */
        public static PaymentResponse timeout(String orderId, BigDecimal amount) {
            return new PaymentResponse(
                    generatePendingId(),
                    orderId,
                    amount,
                    PaymentStatus.PENDING,
                    "결제 처리 시간이 초과되었습니다. 자동으로 재시도됩니다.",
                    Instant.now(),
                    ResponseSource.FALLBACK_TIMEOUT
            );
        }

        private static String generatePendingId() {
            return "PENDING-" + System.currentTimeMillis();
        }
    }

    /**
     * 결제 상태
     */
    public enum PaymentStatus {
        PENDING,        // 처리 대기 (Fallback 상태)
        PROCESSING,     // 처리 중
        COMPLETED,      // 완료
        FAILED,         // 실패
        CANCELLED       // 취소
    }

    /**
     * 응답 출처 (디버깅/모니터링용)
     */
    public enum ResponseSource {
        PRIMARY,                    // 정상 응답
        FALLBACK_CIRCUIT_OPEN,      // Circuit Breaker OPEN으로 인한 Fallback
        FALLBACK_ERROR,             // 예외 발생으로 인한 Fallback
        FALLBACK_TIMEOUT            // 타임아웃으로 인한 Fallback
    }
}
