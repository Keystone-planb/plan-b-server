package com.planb.planb_backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * [장애 주입 테스트] Redis가 장애 상태일 때 실제로 서비스가 안 죽고
 * 넘어가는지 검증. RedisConfig의 CacheErrorHandler는 "Redis 장애 시
 * 캐시 없이 실제 메서드 실행"을 의도하고 만들어졌는데, 지금까지는 코드
 * 주석으로만 그렇게 "주장"하고 있었고 실제로 예외를 안 던지는지 확인하는
 * 테스트가 없었음.
 */
@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

    @Mock private Cache cache;

    private final RedisConfig redisConfig = new RedisConfig();

    @Test
    @DisplayName("캐시 GET 실패 시 예외를 삼키고 정상 반환 — Redis 장애가 서비스 장애로 안 번짐")
    void handleCacheGetError_swallowsException() {
        when(cache.getName()).thenReturn("placeAnalysisStatus");
        CacheErrorHandler handler = redisConfig.errorHandler();
        RuntimeException redisDown = new RuntimeException("Redis 연결 끊김 (시뮬레이션)");

        assertThatCode(() -> handler.handleCacheGetError(redisDown, cache, "key1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("캐시 PUT 실패 시 예외를 삼키고 정상 반환")
    void handleCachePutError_swallowsException() {
        when(cache.getName()).thenReturn("placeAnalysisStatus");
        CacheErrorHandler handler = redisConfig.errorHandler();
        RuntimeException redisDown = new RuntimeException("Redis 연결 끊김 (시뮬레이션)");

        assertThatCode(() -> handler.handleCachePutError(redisDown, cache, "key1", "value"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("캐시 EVICT 실패 시 예외를 삼키고 정상 반환")
    void handleCacheEvictError_swallowsException() {
        when(cache.getName()).thenReturn("tripList");
        CacheErrorHandler handler = redisConfig.errorHandler();
        RuntimeException redisDown = new RuntimeException("Redis 연결 끊김 (시뮬레이션)");

        assertThatCode(() -> handler.handleCacheEvictError(redisDown, cache, "key1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("캐시 CLEAR 실패 시 예외를 삼키고 정상 반환")
    void handleCacheClearError_swallowsException() {
        when(cache.getName()).thenReturn("tripList");
        CacheErrorHandler handler = redisConfig.errorHandler();
        RuntimeException redisDown = new RuntimeException("Redis 연결 끊김 (시뮬레이션)");

        assertThatCode(() -> handler.handleCacheClearError(redisDown, cache))
                .doesNotThrowAnyException();
    }
}
