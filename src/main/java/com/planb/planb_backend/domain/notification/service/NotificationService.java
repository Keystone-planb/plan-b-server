package com.planb.planb_backend.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.notification.dto.AlternativePlaceDto;
import com.planb.planb_backend.domain.notification.dto.NotificationResponse;
import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.GooglePlaceApiService;
import com.planb.planb_backend.domain.place.service.external.RecommendationService;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
import com.planb.planb_backend.domain.trip.dto.AddLocationResponse;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final TripPlaceRepository    tripPlaceRepository;
    private final PlaceRepository        placeRepository;
    private final UserRepository         userRepository;
    private final PreferenceService      preferenceService;
    private final GooglePlaceApiService  googlePlaceApiService;
    private final RecommendationService  recommendationService;

    private static final int NEARBY_RADIUS_METERS = 8_000;
    private static final int MAX_ALTERNATIVES     = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ───────────────────────────────────────────
    //  GET /api/notifications/{userId}
    // ───────────────────────────────────────────

    /**
     * 미확인 알림 + 대안 카드 목록 조회
     * original_lat/lng 가 있으면 원래 장소 근처에서 실시간 INDOOR 탐색.
     * 없으면 pre-stored alternative_place_ids fallback.
     *
     * @Transactional 제거: fetchLiveAlternatives → recommendationService.getRecommendations
     * 내부에서 Google API 호출이 발생해 DB 커넥션을 장시간 점유하는 문제 방지.
     * 각 repository 호출은 Spring Data JPA 기본 트랜잭션으로 처리된다.
     */
    public List<NotificationResponse> getUnreadNotifications(Long userId) {
        return notificationRepository
                .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private NotificationResponse toResponse(Notification n) {
        // 영향받는 기존 일정 장소 정보 조회
        AlternativePlaceDto originalPlace = tripPlaceRepository.findById(n.getPlanId())
                .map(tp -> placeRepository.findByGooglePlaceId(tp.getPlaceId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(AlternativePlaceDto::from)
                .orElse(null);

        List<AlternativePlaceDto> alternatives;

        if (n.getOriginalLat() != null && n.getOriginalLng() != null) {
            // 실시간 근처 탐색 — 원래 장소 좌표 기반
            alternatives = fetchLiveAlternatives(n);
        } else {
            // fallback: pre-stored IDs
            alternatives = parseAltIds(n.getAlternativePlaceIds()).stream()
                    .map(id -> placeRepository.findById(id).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .map(AlternativePlaceDto::from)
                    .collect(Collectors.toList());
        }

        return NotificationResponse.builder()
                .id(n.getId())
                .planId(n.getPlanId())
                .type(n.getType())
                .title(n.getTitle())
                .body(n.getBody())
                .precipitationProb(n.getPrecipitationProb())
                .createdAt(n.getCreatedAt())
                .originalPlace(originalPlace)
                .alternatives(alternatives)
                .build();
    }

    /**
     * AI 분석 기반 실내(INDOOR) 대안 장소 3개 추천
     * 비 오는 날씨 알림이므로 카테고리 무관하게 INDOOR/MIX 장소 우선 반환.
     * AI 분석 미완료(space=null) 장소는 후보로 포함해 결과 부족 방지.
     */
    private List<AlternativePlaceDto> fetchLiveAlternatives(Notification n) {
        // 원래 장소 Google Place ID 조회 (명시적 제외용)
        String originalGooglePlaceId = tripPlaceRepository.findById(n.getPlanId())
                .map(tp -> tp.getPlaceId())
                .orElse(null);

        Long tripId = tripPlaceRepository.findById(n.getPlanId())
                .map(tp -> tp.getItinerary().getTrip().getTripId())
                .orElse(null);

        UserContext ctx = UserContext.builder()
                .userId(n.getUserId())
                .tripId(tripId)
                .currentPlanId(n.getPlanId())
                .currentLat(n.getOriginalLat())
                .currentLng(n.getOriginalLng())
                .radiusMinute(20)
                .keepOriginalCategory(false)   // 카테고리 무관 — 실내 어디든
                .considerNextPlan(false)
                .build();

        List<Place> all = recommendationService.getRecommendations(ctx);

        // 원래 장소(강릉역 등) 명시적 제외 — collectExcludedPlaceIds 미스 방지
        final String origId = originalGooglePlaceId;
        List<Place> filtered = (origId == null) ? all : all.stream()
                .filter(p -> !java.util.Objects.equals(p.getGooglePlaceId(), origId))
                .collect(Collectors.toList());

        // INDOOR / MIX 우선, 없으면 전체 후보에서 선택 (AI 미분석 장소 포함)
        List<Place> indoor = filtered.stream()
                .filter(p -> p.getSpace() == com.planb.planb_backend.domain.trip.entity.Space.INDOOR
                          || p.getSpace() == com.planb.planb_backend.domain.trip.entity.Space.MIX)
                .collect(Collectors.toList());

        List<Place> candidates = indoor.isEmpty() ? filtered : indoor;

        List<AlternativePlaceDto> dtos = candidates.stream()
                .limit(MAX_ALTERNATIVES)
                .map(AlternativePlaceDto::from)
                .collect(Collectors.toList());

        // replacePlan() 검증용 캐시
        List<Long> altIds = dtos.stream()
                .map(AlternativePlaceDto::getPlaceId)
                .collect(Collectors.toList());
        try {
            n.setAlternativePlaceIds(objectMapper.writeValueAsString(altIds));
            notificationRepository.save(n);
        } catch (Exception e) {
            log.warn("[Notification] altIds 캐시 저장 실패: {}", e.getMessage());
        }

        log.info("[Notification] AI 대안 추천 완료 — notificationId={}, {}개", n.getId(), dtos.size());
        return dtos;
    }

    // ───────────────────────────────────────────
    //  POST /api/notifications/{notificationId}/replace/{newPlaceId}
    // ───────────────────────────────────────────

    /**
     * 대안으로 일정 교체 — 4가지 부수 효과를 한 트랜잭션으로 처리
     * 1) TripPlace.placeId 변경
     * 2) TripPlace.name → '{새 장소명} (PLAN B)'
     * 3) Notification.isRead = true
     * 4) 피드백 반영: 선택 장소 mood +1.0, 나머지 -0.3
     *
     * @throws IllegalArgumentException newPlaceId가 알림 대안 목록에 없을 때 (400)
     */
    @Transactional
    public AddLocationResponse replacePlan(Long notificationId, Long newPlaceId, String userEmail) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        validateOwner(notification.getUserId(), userEmail);

        // 대안 목록 검증
        List<Long> altIds = parseAltIds(notification.getAlternativePlaceIds());
        if (!altIds.contains(newPlaceId)) {
            throw new IllegalArgumentException("선택한 장소가 이 알림의 대안 목록에 없습니다.");
        }

        // 1) + 2) TripPlace 교체 (기존 행 in-place 업데이트 — 새 행 추가 없음)
        TripPlace tripPlace = tripPlaceRepository.findById(notification.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없습니다."));

        Place newPlace = placeRepository.findById(newPlaceId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다."));

        tripPlace.replace(newPlace.getGooglePlaceId(), newPlace.getName());
        TripPlace saved = tripPlaceRepository.save(tripPlace);

        // 3) 알림 읽음 처리
        notification.setRead(true);
        notificationRepository.save(notification);

        // 4) 피드백: 선택 +1.0 / 나머지 -0.3
        preferenceService.applyFeedback(notification.getUserId(), altIds, newPlaceId);

        log.info("[Notification] 일정 교체 완료 — planId={}, newPlaceId={}", notification.getPlanId(), newPlaceId);
        return AddLocationResponse.from(saved);
    }

    // ───────────────────────────────────────────
    //  POST /api/notifications/{notificationId}/dismiss
    // ───────────────────────────────────────────

    /**
     * 알림 그냥 닫기 — is_read = true 만 갱신
     */
    @Transactional
    public void dismiss(Long notificationId, String userEmail) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        validateOwner(notification.getUserId(), userEmail);

        notification.setRead(true);
        notificationRepository.save(notification);
        log.info("[Notification] 알림 닫기 — notificationId={}", notificationId);
    }

    // ───────────────────────────────────────────
    //  [테스트용] 날씨 알림 시드 생성
    // ───────────────────────────────────────────

    /**
     * 테스트용 날씨 알림 강제 생성
     * 날씨 API / 강수 확률 조건 / 날짜 범위를 모두 우회하여 즉시 알림을 삽입한다.
     *
     * @param userId      알림 수신 사용자 ID
     * @param tripPlaceId 원래 일정 TripPlace PK (planId로 사용됨)
     */
    @Transactional
    public NotificationResponse seedTestNotification(Long userId, Long tripPlaceId) {
        TripPlace tp = tripPlaceRepository.findById(tripPlaceId)
                .orElseThrow(() -> new IllegalArgumentException("tripPlaceId=" + tripPlaceId + " 를 찾을 수 없습니다."));

        Place originalPlace = placeRepository.findByGooglePlaceId(tp.getPlaceId()).orElse(null);

        Notification n = new Notification();
        n.setUserId(userId);
        n.setPlanId(tripPlaceId);
        n.setType("WEATHER_RAIN");
        n.setTitle("[테스트] 비가 올 예정이에요!");
        n.setBody(tp.getName() + " 방문 시간에 비가 올 것 같아요. 실내 대안 장소를 확인해보세요.");
        n.setPrecipitationProb(85); // 테스트용 고정 강수 확률
        n.setAlternativePlaceIds("[]");

        if (originalPlace != null) {
            n.setOriginalLat(originalPlace.getLatitude());
            n.setOriginalLng(originalPlace.getLongitude());
        }

        Notification saved = notificationRepository.save(n);
        log.info("[Notification][SEED] 테스트 알림 생성 완료 — notificationId={}, userId={}, tripPlaceId={}",
                saved.getId(), userId, tripPlaceId);

        return toResponse(saved);
    }

    // ───────────────────────────────────────────
    //  내부 헬퍼
    // ───────────────────────────────────────────

    private List<Long> parseAltIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("[Notification] alternativePlaceIds 파싱실패: {}", json);
            return List.of();
        }
    }

    private void validateOwner(Long ownerId, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (!ownerId.equals(user.getId())) {
            throw new SecurityException("본인의 알림만 접근할 수 있습니다.");
        }
    }
}
