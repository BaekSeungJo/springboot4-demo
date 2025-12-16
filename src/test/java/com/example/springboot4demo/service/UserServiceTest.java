package com.example.springboot4demo.service;

import com.example.springboot4demo.client.User;
import com.example.springboot4demo.client.UserClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserClient userClient;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("모든 사용자를 성공적으로 조회한다.")
    void getAllUsers_Success() {
        // Given - 테스트 데이터 생성
        List<User> expectedUsers = List.of(
                new User(1L, "John Doe", "john@example.com", "johndoe"),
                new User(2L, "Jane Smith", "jane@example.com", "janesmith")
        );
        given(userClient.getAllUsers()).willReturn(expectedUsers);

        // When - 테스트 실행
        List<User> actualUsers = userService.getAllUsers();

        // Then - 검증
        assertThat(actualUsers)
                .hasSize(2)
                .extracting(User::name)
                .containsExactly("John Doe", "Jane Smith");

        then(userClient).should(times(1)).getAllUsers();
    }

    @Test
    @DisplayName("외부 API 호출 실패 시 예외를 발생시킨다.")
    void getAllUsers_ExternalApiFailure_ThrowsException() {
        // Given
        given(userClient.getAllUsers())
                .willThrow(new RuntimeException("API connection failed"));

        // When & Then
        assertThatThrownBy(() -> userService.getAllUsers())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("External API call failed");
    }

}