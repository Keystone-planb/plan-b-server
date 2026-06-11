package com.planb.planb_backend.domain.notification.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.notification.service.ExpoPushService;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.GooglePlaceApiService;
import com.planb.planb_backend.domain.place.service.external.WeatherApiService;
import com.planb.planb_backend.domain.trip.entity.Space;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 날씨 알림 스케줄러
 *
 * [실행 주기] 4시간마다 (fixedRate = 4h)
 * [대상 범위] 현재 시점부터 24시간 이내의 일정
 * [Skip 조건]
 *   1. 원래 일정 장소가 INDOOR
 *   2. 동일 planId에 24시간 내 알림 발송 이력 존재
 *   3. 강수 확률(POP) 70% 미만
 *   4. 대안 장소를 1개도 찾지 못한 경우
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherScheduler {

    private final TripPlaceRepository    tripPlaceRepository;
    private final PlaceRepository        placeRepository;
    private final NotificationRepository notificationRepository;
    private final GooglePlaceApiService  googlePlaceApiService;
    private final WeatherApiService      weatherApiService;
    private final UserRepository         userRepository;
    private final ExpoPushService        expoPushService;

    private static final int POP_THRESHOLD    = 70;   // 강수 확률 임계값 (%)
    private static final int RADIUS_METERS    = 8_000; // 20분 차량 반경 (400m × 20)
    private static final int MAX_ALTERNATIVES = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // @Transactional 제거: findForScheduler JOIN FETCH로 itinerary·trip·user 선로딩
    // → 외부 API(날씨·Google·ExpoPush) 호출 중 DB 커넥션 장시간 점유 문제 해소
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000L)
    public void checkWeatherAndNotify() {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today    = LocalDate.now(kst);
        LocalDate tomorrow = today.plusDays(1);
        LocalDateTime now  = LocalDateTime.now(kst);
        LocalDateTime cutoff = now.plusHours(24);

        log.info("[WeatherScheduler] 실행 시작 — 대상 범위: {} ~ {}", now, cutoff);

        List<TripPlace> candidates = tripPlaceRepository.findForScheduler(today, tomorrow);
        log.info("[WeatherScheduler] 조회된 일정: {}건", candidates.size());

        for (TripPlace tp : candidates) {
            try {
                processTripPlace(tp, now, cutoff);
            } catch (Exception e) {
                log.warn("[WeatherScheduler] 일정 처리 중 오류 (planId={}): {}", tp.getTripPlaceId(), e.getMessage());
            }
        }
        log.info("[WeatherScheduler] 실행 완료");
    }

    private void processTripPlace(TripPlace tp, LocalDateTime now, LocalDateTime cutoff) {
        Long planId = tp.getTripPlaceId();
        log.info("[WeatherScheduler] ▶ 일정 확인 시작 — planId={}, 장소={}, 방문시각={}",
                planId, tp.getName(), tp.getVisitTime());

        // [Skip 1] 방문 시각이 24시간 범위 밖
        LocalDateTime visitDateTime = toVisitDateTime(tp);
        if (visitDateTime == null) {
            log.info("[WeatherScheduler]   ✗ SKIP — 방문 시각 미설정 (planId={})", planId);
            return;
        }
        if (visitDateTime.isBefore(now)) {
            log.info("[WeatherScheduler]   ✗ SKIP — 이미 지난 일정 (planId={}, 방문={}, 현재={})", planId, visitDateTime, now);
            return;
        }
        if (visitDateTime.isAfter(cutoff)) {
            log.info("[WeatherScheduler]   ✗ SKIP — 24시간 초과 일정 (planId={}, 방문={})", planId, visitDateTime);
            return;
        }
        log.info("[WeatherScheduler]   ✓ 시간 범위 통과 — planId={}, 방문={}", planId, visitDateTime);

        // 원래 장소 조회
        Place originalPlace = placeRepository.findByGooglePlaceId(tp.getPlaceId()).orElse(null);
        if (originalPlace == null || originalPlace.getLatitude() == null) {
            log.info("[WeatherScheduler]   ✗ SKIP — 장소 좌표 없음 (planId={}, placeId={})", planId, tp.getPlaceId());
            return;
        }

        // [Skip 2] 원래 장소가 INDOOR
        if (Space.INDOOR == originalPlace.getSpace()) {
            log.info("[WeatherScheduler]   ✗ SKIP — INDOOR 장소 (planId={}, 장소={})", planId, tp.getName());
            return;
        }
        log.info("[WeatherScheduler]   ✓ 공간 타입 통과 — planId={}, space={}", planId, originalPlace.getSpace());

        // [Skip 3] 24시간 내 동일 planId + 동일 장소(좌표) 알림 발송 이력
        // 장소를 교체한 경우(좌표 변경)는 중복으로 보지 않아 새 알림 허용
        if (notificationRepository.existsByPlanIdAndCreatedAtAfterAndOriginalLatAndOriginalLng(
                planId, now.minusHours(24),
                originalPlace.getLatitude(), originalPlace.getLongitude())) {
            log.info("[WeatherScheduler]   ✗ SKIP — 24시간 내 동일 장소 중복 알림 존재 (planId={}, lat={}, lng={})",
                    planId, originalPlace.getLatitude(), originalPlace.getLongitude());
            return;
        }
        log.info("[WeatherScheduler]   ✓ 중복 알림 없음 — planId={}", planId);

        // [Skip 4] 강수 확률 70% 미만
        int pop = weatherApiService.getPrecipitationProbability(
                originalPlace.getLatitude(), originalPlace.getLongitude(), visitDateTime);
        if (pop < POP_THRESHOLD) {
            log.info("[WeatherScheduler]   ✗ SKIP — 강수 확률 미달 (planId={}, pop={}%, 기준={}%)", planId, pop, POP_THRESHOLD);
            return;
        }
        log.info("[WeatherScheduler]   ✓ 강수 확률 통과 — planId={}, pop={}%", planId, pop);

        log.info("[WeatherScheduler] 알림 생성 대상 — planId={}, 장소={}, POP={}%",
                planId, tp.getName(), pop);

        // 알림 생성 (대안은 조회 시점에 실시간 탐색)
        Notification notification = buildNotification(tp, pop, originalPlace);
        notificationRepository.save(notification);
        log.info("[WeatherScheduler] 알림 저장 완료 — planId={}, userId={}",
                planId, notification.getUserId());

        // 푸시 알림 발송
        sendPushIfTokenExists(notification, tp);
    }

    /**
     * 반경 20분(차량) 내 INDOOR 대안 장소 최대 3개 탐색
     * 원래 카테고리가 실외 전용(PARK 등)이면 "cafe"로 변환
     */
    private List<Long> findIndoorAlternatives(Place originalPlace) {
        String indoorCategory = toIndoorCategory(originalPlace);

        List<Map<String, Object>> results = googlePlaceApiService.searchNearbyPlaces(
                originalPlace.getLatitude(),
                originalPlace.getLongitude(),
                RADIUS_METERS,
                indoorCategory
        );

        List<Long> altIds = new ArrayList<>();
        for (Map<String, Object> result : results) {
            if (altIds.size() >= MAX_ALTERNATIVES) break;

            String gId = (String) result.get("place_id");
            if (gId == null) continue;

            // OPERATIONAL 필터
            String bsRaw = (String) result.get("business_status");
            if (bsRaw != null && !BusinessStatus.OPERATIONAL.name().equalsIgnoreCase(bsRaw)) continue;

            // DB Upsert
            Place alt = placeRepository.findByGooglePlaceId(gId).orElseGet(() -> {
                Place p = new Place();
                p.setGooglePlaceId(gId);
                p.setName((String) result.get("name"));
                p.setSpace(Space.INDOOR);
                return placeRepository.save(p);
            });

            altIds.add(alt.getId());
        }
        return altIds;
    }

    /**
     * 원래 카테고리 → INDOOR 대안 카테고리 매핑
     */
    private String toIndoorCategory(Place place) {
        if (place.getType() == null) return "cafe";
        return switch (place.getType()) {
            case PARK    -> "cafe";
            case SIGHTS  -> "museum";
            case MARKET  -> "shopping_mall";
            default      -> "cafe"; // FOOD, CAFE 등 실내 가능한 카테고리 유지
        };
    }

    private Notification buildNotification(TripPlace tp, int pop, Place originalPlace) {
        Long userId = tp.getItinerary().getTrip().getUser().getId();

        Notification n = new Notification();
        n.setUserId(userId);
        n.setPlanId(tp.getTripPlaceId());
        n.setType("WEATHER_RAIN");
        n.setTitle("비가 올 예정이에요!");
        n.setBody(tp.getName() + " 방문 시간에 비가 올 것 같아요. 실내 대안 장소를 확인해보세요.");
        n.setPrecipitationProb(pop);
        n.setAlternativePlaceIds("[]");
        n.setOriginalLat(originalPlace.getLatitude());
        n.setOriginalLng(originalPlace.getLongitude());
        return n;
    }

    private void sendPushIfTokenExists(Notification notification, TripPlace tp) {
        try {
            userRepository.findById(notification.getUserId()).ifPresent(user -> {
                String token = user.getExpoPushToken();
                if (token == null || token.isBlank()) {
                    log.info("[WeatherScheduler] 푸시 토큰 없음 — userId={}", notification.getUserId());
                    return;
                }

                Long tripId = tp.getItinerary().getTrip().getTripId();
                Map<String, Object> data = Map.of(
                        "notificationId", notification.getId(),
                        "tripId",         tripId,
                        "tripPlaceId",    notification.getPlanId()
                );

                expoPushService.sendPush(token, notification.getTitle(), notification.getBody(), data);

                notification.setPushSentAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")));
                log.info("[WeatherScheduler] 푸시 발송 완료 — userId={}, notificationId={}",
                        notification.getUserId(), notification.getId());
            });
        } catch (Exception e) {
            log.warn("[WeatherScheduler] 푸시 발송 중 오류 — userId={}, error={}",
                    notification.getUserId(), e.getMessage());
        }
    }

    private LocalDateTime toVisitDateTime(TripPlace tp) {
        if (tp.getVisitTime() == null || tp.getVisitTime().isBlank()) return null;
        try {
            LocalTime time = LocalTime.parse(tp.getVisitTime());
            return LocalDateTime.of(tp.getItinerary().getDate(), time);
        } catch (Exception e) {
            return null;
        }
    }
}
