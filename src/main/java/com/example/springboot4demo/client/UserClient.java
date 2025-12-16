package com.example.springboot4demo.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * 선언적 HTTP 클라이언트
 * <p>
 * Spring Boot 4.0의 새로운 기능:
 * - 인터페이스만 정의하면 구현체 자동 생성
 * - RestTemplate/WebClient보다 간결
 * - 타입 안정성 보장
 */
@HttpExchange(url = "https://jsonplaceholder.typicode.com")
public interface UserClient {

    /**
     * 사용자 목록 조회
     *
     * @return 사용자 목록
     */
    @GetExchange("/users")
    List<User> getAllUsers();

    /**
     * 특정 사용자 조회
     *
     * @param id 사용자 ID
     * @return 사용자 정보
     */
    @GetExchange("/users/{id}")
    User getUserById(@PathVariable Long id);

    /**
     * 사용자 생성
     *
     * @param user 생성할 사용자 정보
     * @return 생성된 사용자
     */
    @PostExchange("/users")
    User createUser(@RequestBody User user);
}
