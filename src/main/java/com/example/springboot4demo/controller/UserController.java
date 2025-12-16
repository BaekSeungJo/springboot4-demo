package com.example.springboot4demo.controller;

import com.example.springboot4demo.client.User;
import com.example.springboot4demo.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 사용자 REST API
 *
 * Spring Boot 4.0 Best Practices:
 * - Record 타입 사용 (DTO)
 * - ResponseEntity로 HTTP 상태 명시
 * - 적절한 HTTP 메서드 사용
 *
 * 전체 버전 통합 컨트롤러
 * - 버전별로 별도 컨트롤러 분리하지 않음
 * - 같은 리소스는 하나의 컨트롤러에서 관리
 * - version 속성으로 버전 구분
 */

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }
}
