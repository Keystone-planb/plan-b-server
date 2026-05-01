package com.planb.planb_backend.domain.place.service.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 혼잡도 제보 서비스 (Redis TTL 2시간)
 *
 * 키 구조: congestion:{googlePlaceId}
 * 값    : "1" (존재 여부만 체크)
 * TTL   : 2시간 (제보 후 2시간 내에만 유효)
 *
 * isCongested() 는 Redis 장애 시 false를 반환하여 서비스 차단을 방지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CongestionService {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "congestion:";
    private static final Duration CONGESTION_TTL = Duration.ofHours(2);

    /**
     * 해당 장소에 유효한 혼잡 제보(TTL 이내)가 있는지 확인
     */
    public boolean isCongested(String googlePlaceId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + googlePlaceId));
        } catch (Exception e) {
            log.warn("[혼잡도 조회 실패] Redis 연결 오류, 페널티 미적용: {}", e.getMessage());
            return false; // Redis 장애 시 차단 없이 통과
        }
    }

    /**
     * 혼잡도 제보 등록 (사용자 제보 API에서 호출)
     */
    public void reportCongestion(String googlePlaceId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + googlePlaceId, "1", CONGESTION_TTL);
            log.info("[혼잡 제보 등록] googlePlaceId={}, TTL=2h", googlePlaceId);
        } catch (Exception e) {
            log.warn("[혼잡 제보 등록 실패] Redis 연결 오류: {}", e.getMessage());
        }
    }
}
