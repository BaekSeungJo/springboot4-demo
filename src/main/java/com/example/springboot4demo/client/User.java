package com.example.springboot4demo.client;

/**
 * 사용자 DTO
 *
 * JSpecify를 활용한 NULL 안정성:
 * - @NonNull: null이 될 수 없음
 * - @Nullable: null이 될 수 있음
 */
public record User(
        Long id,
        String name,
        String email,
        String username
) {
    // Record는 불편 객체이므로 생성자, getter, equals, hashCode 자동 생성
}
