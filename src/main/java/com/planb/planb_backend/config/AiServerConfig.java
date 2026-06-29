package com.planb.planb_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Python AI 서버(FastAPI) 호출용 WebClient Bean
 * - AI_SERVER_URL 환경변수가 없으면 로컬 기본값(http://localhost:8000) 사용
 */
@Configuration
public class AiServerConfig {

    @Value("${ai-server.base-url}")
    private String aiServerBaseUrl;

    @Bean
    public WebClient aiServerWebClient() {
        return WebClient.builder()
                .baseUrl(aiServerBaseUrl)
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)
                )
                .build();
        }
}
