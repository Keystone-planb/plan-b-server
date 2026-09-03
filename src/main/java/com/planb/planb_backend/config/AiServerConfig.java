package com.planb.planb_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Python AI 서버(FastAPI) 호출용 WebClient Bean
 * - AI_SERVER_URL 환경변수가 없으면 로컬 기본값(http://localhost:8000) 사용
 *
 * [전용 커넥션 풀] 원래는 Reactor Netty 공유 기본 풀(전역 500개, 다른 리액티브 클라이언트와
 * 암묵적으로 공유)에 얹혀갔음 — 지금 병목은 아니지만(Python 쪽 세마포어(25)가 더 먼저 걸림),
 * "얼마나 쓰고 있는지 안 보이는 공용 풀"보다 이름 붙은 전용 풀로 분리해서 관측 가능하게 함.
 * .metrics(true)로 /actuator/prometheus에 커넥션 사용량 노출
 */
@Configuration
public class AiServerConfig {

    @Value("${ai-server.base-url}")
    private String aiServerBaseUrl;

    private static final ConnectionProvider AI_SERVER_POOL = ConnectionProvider.builder("ai-server-pool")
            .maxConnections(100)
            .maxIdleTime(Duration.ofSeconds(30))
            .pendingAcquireTimeout(Duration.ofSeconds(30))
            .metrics(true)
            .build();

    @Bean
    public WebClient aiServerWebClient() {
        return WebClient.builder()
                .baseUrl(aiServerBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create(AI_SERVER_POOL)))
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)
                )
                .build();
    }
}
