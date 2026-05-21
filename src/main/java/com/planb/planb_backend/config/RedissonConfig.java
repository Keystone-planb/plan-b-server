package com.planb.planb_backend.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 분산 락 설정
 * - spring-boot-starter-data-redis(Lettuce)와 별도로 Redisson 클라이언트만 등록
 * - 동일한 Redis 서버를 바라보되 빈 충돌 없이 독립적으로 동작
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // SSL 활성화 시 rediss://, 비활성화 시 redis://
        String scheme = sslEnabled ? "rediss" : "redis";
        config.useSingleServer()
                .setAddress(scheme + "://" + host + ":" + port)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        return Redisson.create(config);
    }
}
