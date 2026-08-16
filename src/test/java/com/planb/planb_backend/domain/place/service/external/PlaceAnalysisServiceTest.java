package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * [동시성 테스트] 같은 장소에 대한 동시 분석 요청을 Redisson 분산 락이
 * 실제로 막아주는지 검증. 락 획득에 실패하면(다른 스레드가 이미 분석 중)
 * DB 조회/쓰기를 전혀 시도하지 않고 바로 스킵해야 한다 — 중복 분석 방지 목적.
 */
@ExtendWith(MockitoExtension.class)
class PlaceAnalysisServiceTest {

    @Mock private PlaceRepository       placeRepository;
    @Mock private OpenAiAnalysisService openAiAnalysisService;
    @Mock private GooglePlaceApiService googlePlaceApiService;
    @Mock private NaverApiService       naverApiService;
    @Mock private RedissonClient        redissonClient;
    @Mock private CacheManager          cacheManager;
    @Mock private RLock                 lock;

    @Test
    @DisplayName("분산 락 획득 실패(이미 분석 중) — DB 조회/쓰기 없이 즉시 스킵")
    void triggerAnalysisAsync_lockNotAcquired_skipsWithoutDbAccess() throws InterruptedException {
        PlaceAnalysisService service = new PlaceAnalysisService(
                placeRepository, openAiAnalysisService, googlePlaceApiService,
                naverApiService, redissonClient, cacheManager);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 이미 다른 요청이 같은 장소를 분석 중 — 락 획득 실패
        when(lock.tryLock(0, 90, TimeUnit.SECONDS)).thenReturn(false);

        service.triggerAnalysisAsync("ChIJconcurrent");

        // 락을 못 잡았으면 place 조회/저장을 전혀 시도하면 안 됨 — 중복 분석 방지가 실제로 동작하는지 확인
        verifyNoInteractions(placeRepository);
        verifyNoInteractions(openAiAnalysisService);
        verifyNoInteractions(googlePlaceApiService);

        // 락 획득 실패 시 unlock을 호출하면 안 됨 (isHeldByCurrentThread 가드가 있어야 함 — 안 그러면 다른 스레드의 락을 풀어버리는 버그)
        verify(lock, never()).unlock();
    }
}
