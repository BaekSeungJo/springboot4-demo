package com.example.springboot4demo.exception;

/**
 * 비즈니스 예외 (비즈니스 규칙 위반)
 * <p>
 * 이 예외는 Circuit Breaker가 무시합니다. (실패로 카운트하지 않음).
 * <p>
 * 왜 무시해야 하는가?
 * - 잔액 부족, 재고 없음 등은 "시스템 장애"가 아닙니다
 * - 이런 예외로 Circuit을 열면 정상 요청도 차단됩니다
 * - 비즈니스 로직의 정상적인 실패 응답입니다
 * <p>
 * 무시어되어야 하는 상황
 * - 잔액 부족
 * - 재고 없음
 * - 유효하지 않은 주문
 * - 중복 요청
 * - 권한 없음
 */
public class BusinessException extends RuntimeException {
    private final String errorCode;
    private final Object details;

    public BusinessException(
            String errorCode,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(
            String errorCode,
            String message,
            Object details
    ) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public String errorCode() {
        return errorCode;
    }

    public Object details() {
        return details;
    }

    // 자주 사용되는 비즈니스 예외를 위한 팩토리 메서드
    public static BusinessException insufficientBalance(
            String accountId,
            long required,
            long available
    ) {
        return new BusinessException(
                "INSUFFICIENT_BALANCE",
                String.format("잔액이 부족합니다. 필요: %d, 보유: %d", required, available),
                new InsufficientBalanceDetails(accountId, required, available)
        );
    }

    public static BusinessException outOfStock(String productId, int requested, int available) {
        return new BusinessException(
                "OUT_OF_STOCK",
                String.format("재고가 부족합니다. 요청: %d, 가용: %d", requested, available),
                new OutOfStockDetails(productId, requested, available)
        );
    }

    public static BusinessException duplicateRequest(String requestId) {
        return new BusinessException(
                "DUPLICATE_REQUEST",
                String.format("중복 요청입니다. requestId: %s", requestId)
        );
    }

    // 상세 정보 Records
    public record InsufficientBalanceDetails(String accountId, long required, long available) {}
    public record OutOfStockDetails(String productId, int requested, int available) {}
}
