package com.example.springboot4demo.service;

import com.example.springboot4demo.client.User;
import com.example.springboot4demo.client.UserClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 사용자 서비스
 * <p>
 * 실무 패턴
 * - Client와 Service 분리 (관림사 분리)
 * - 예외 처리 및 로깅
 * - 캐싱 고려
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserClient userClient;

    public UserService(UserClient userClient) {
        this.userClient = userClient;
    }

    /**
     * 모든 사용자 조회
     * <p>
     * 주의사항:
     * - 외부 API 호출이므로 타임아웃 발생 가능
     * - 프로덕션에서는 Circuit Breaker 적용 권장
     */
    public List<User> getAllUsers() {
        log.info("Fetching all users from external API");
        try {
            List<User> users = userClient.getAllUsers();
            log.info("Successfully fetched {} users", users.size());
            return users;
        } catch (Exception e) {
            log.error("Failed to fetch users", e);
            throw new RuntimeException("External API call failed", e);
        }
    }

    /**
     * 특정 사용자 조회
     */
    public User getUserById(Long id) {
        log.info("Fetching user with id: {}", id);
        return userClient.getUserById(id);
    }
}
