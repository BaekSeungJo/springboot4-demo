package com.example.springboot4demo.config;

import com.example.springboot4demo.client.UserClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP Service Client 설정
 * <p>
 * Spring Boot 4.0 방식:
 * - RestClient 기반 (RestTemplate 대체)
 * - HttpServiceProxyFactory로 프록시 생성
 */
@Configuration
public class HttpClientConfig {

    /**
     * RestClient 빈 생성
     * <p>
     * RestTemplate vs RestClient
     * - RestClient: 현대적, 플루언트 API, 타입 안정성
     * - RestTemplate: 레거시, deprecated 예정
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .baseUrl("https://jsonplaceholder.typicode.com")
                // 타임아웃 설정 ( 프로덕션 필수 )
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                // 연결 타임아웃: 5초
                                .connectTimeout(Duration.ofSeconds(5))
                                .build()
                ) {
                    {
                        // 읽기 타임아웃: 10초
                        setReadTimeout(Duration.ofSeconds(10));
                    }
                })
                .build();
    }


    /**
     * UserClient 프록시 생성
     * <p>
     * 동작 방식:
     * 1. RestClientAdapter: RestClient를 HttpExchangeAdapter로 변환
     * 2. HttpServiceProxyFactory: 인터페이스 프록시 생성
     * 3. 런타임에 HTTP 요청 자동 수행
     */
    @Bean
    public UserClient userClient(RestClient restClient) {
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(adapter)
                .build();

        return factory.createClient(UserClient.class);
    }
}
