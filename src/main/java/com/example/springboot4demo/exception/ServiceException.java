package com.example.springboot4demo.exception;

/**
 * 서비스 예외 (시스템 장애)
 *
 * 이 예외는 Circuit Breaker가 "실패"로 카운트합니다.
 * 외부 서비스 장애, 네트워크 오류, 타임아웃 등에 사용합니다.
 *
 * 실패로 기록되어야 하는 상황:
 * - 외부 서비스 응답 없음
 * - 네트워크 연결 실패
 * - 서버 내부 오류 (5xx)
 * - 타임아웃
 */
public class ServiceException extends RuntimeException {
    private final String serviceName;
    private final String errorCode;

    public ServiceException(
            String serviceName,
            String message
    ) {
        super(message);
        this.serviceName = serviceName;
        this.errorCode = "SERVICE_ERROR";
    }

    public ServiceException(
            String serviceName,
            String errorCode,
            String message
    ) {
        super(message);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
    }

    public ServiceException(String serviceName, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
        this.errorCode = "SERVICE_ERROR";
    }

    public String serviceName() {
        return serviceName;
    }

    public String errorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return String.format("ServiceException[service=%s, code=%s, message=%s]",
                serviceName, errorCode, getMessage());
    }
}
