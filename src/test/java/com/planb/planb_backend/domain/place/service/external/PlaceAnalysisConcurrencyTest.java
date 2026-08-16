package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * [진짜 동시성 테스트] Mock이 아니라 실제 로컬 Redis(localhost:6379)에 붙은
 * RedissonClient로, 실제 스레드 10개가 CountDownLatch로 동시에 출발해서
 * 같은 장소를 동시에 분석 요청했을 때 분산 락이 실제로 경쟁을 막아주는지 검증.
 *
 * PlaceAnalysisServiceTest(단일 스레드, tryLock을 mock으로 false 고정)와의 차이:
 * 여기는 진짜 락 경쟁이 일어나고, 그 결과로 실제 실행 횟수가 1번인지를 잰다.
 *
 * 로컬 Redis 또는 CI의 redis 서비스 컨테이너(localhost:6379)가 필요함.
 */
@ExtendWith(MockitoExtension.class)
class PlaceAnalysisConcurrencyTest {

    @Mock private PlaceRepository       placeRepository;
    @Mock private OpenAiAnalysisService openAiAnalysisService;
    @Mock private GooglePlaceApiService googlePlaceApiService;
    @Mock private NaverApiService       naverApiService;
    @Mock private CacheManager          cacheManager;

    private RedissonClient redissonClient;
    private PlaceAnalysisService service;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        redissonClient = Redisson.create(config);

        service = new PlaceAnalysisService(
                placeRepository, openAiAnalysisService, googlePlaceApiService,
                naverApiService, redissonClient, cacheManager);
    }

    @AfterEach
    void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @Test
    @DisplayName("같은 장소를 10개 스레드가 동시에 분석 요청해도 실제 분석 로직은 정확히 1번만 실행됨")
    void triggerAnalysisAsync_tenConcurrentThreads_onlyOneActuallyAnalyzes() throws Exception {
        // 테스트마다 락 키가 겹치지 않도록 유니크한 googlePlaceId 사용
        String googlePlaceId = "ChIJconcurrency-test-" + System.nanoTime();

        Place place = new Place();
        place.setGooglePlaceId(googlePlaceId);
        ReflectionTestUtils.setField(place, "id", 1L);

        when(placeRepository.findByGooglePlaceId(googlePlaceId)).thenReturn(Optional.of(place));
        when(placeRepository.findById(1L)).thenReturn(Optional.of(place));
        when(placeRepository.saveAndFlush(any())).thenReturn(place);

        // 락 안에서만 호출되는 지점 — 실제로 몇 번 실행됐는지 여기서 셈
        AtomicInteger executionCount = new AtomicInteger(0);
        when(googlePlaceApiService.getGooglePlaceDetails(googlePlaceId)).thenAnswer(inv -> {
            executionCount.incrementAndGet();
            // 분석이 "진행 중"인 시간을 흉내내서, 다른 스레드들이 tryLock에 걸리는 경쟁 창을 벌림
            Thread.sleep(300);
            return Map.of(); // 빈 응답 → processPlaceAnalysis는 실패 처리되지만 "실행은 됐다"가 핵심
        });

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();               // 모든 스레드가 준비될 때까지 대기 → 동시 출발
                    service.triggerAnalysisAsync(googlePlaceId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue(); // 10개 스레드 전부 대기 상태 진입 확인
        startLatch.countDown();                                     // 동시에 출발!
        boolean finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finishedInTime).isTrue();
        // 10개 스레드가 동시에 요청했어도, 분산 락 덕분에 실제 분석은 딱 1번만 실행돼야 함
        assertThat(executionCount.get()).isEqualTo(1);
    }
}
