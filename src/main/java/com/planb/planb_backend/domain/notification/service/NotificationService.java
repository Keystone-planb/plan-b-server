package com.planb.planb_backend.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.notification.dto.AlternativePlaceDto;
import com.planb.planb_backend.domain.notification.dto.NotificationResponse;
import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.place.service.external.GooglePlaceApiService;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
import com.planb.planb_backend.domain.trip.entity.Space;
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
     */
    @Transactional
    public List<NotificationResponse> getUnreadNotifications(Long userId) {
        return notificationRepository
                .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private NotificationResponse toResponse(Notification n) {
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
                .alternatives(alternatives)
                .build();
    }

    /**
     * 원래 장소 좌표 근처 INDOOR 장소 실시간 탐색 후 DB upsert → 대안 카드 반환.
     * 결과를 alternative_place_ids 에 캐시하여 replacePlan() 검증에서 재사용.
     */
    private List<AlternativePlaceDto> fetchLiveAlternatives(Notification n) {
        List<Map<String, Object>> results = googlePlaceApiService.searchNearbyPlaces(
                n.getOriginalLat(), n.getOriginalLng(), NEARBY_RADIUS_METERS, "cafe");

        List<Long> altIds = new ArrayList<>();
        List<AlternativePlaceDto> dtos = new ArrayList<>();

        for (Map<String, Object> result : results) {
            if (altIds.size() >= MAX_ALTERNATIVES) break;

            String gId = (String) result.get("place_id");
            if (gId == null) continue;

            String bsRaw = (String) result.get("business_status");
            if (bsRaw != null && !BusinessStatus.OPERATIONAL.name().equalsIgnoreCase(bsRaw)) continue;

            Place place = placeRepository.findByGooglePlaceId(gId).orElseGet(() -> {
                Place p = new Place();
                p.setGooglePlaceId(gId);
                p.setName((String) result.get("name"));
                p.setSpace(Space.INDOOR);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> location = geometry != null
                            ? (Map<String, Object>) geometry.get("location") : null;
                    Object lat = location != null ? location.get("lat") : null;
                    Object lng = location != null ? location.get("lng") : null;
                    if (lat != null) p.setLatitude(((Number) lat).doubleValue());
                    if (lng != null) p.setLongitude(((Number) lng).doubleValue());
                } catch (Exception ignored) { }
                return placeRepository.save(p);
            });

            altIds.add(place.getId());
            dtos.add(AlternativePlaceDto.from(place));
        }

        // replacePlan() 검증용으로 캐시
        try {
            n.setAlternativePlaceIds(objectMapper.writeValueAsString(altIds));
            notificationRepository.save(n);
        } catch (Exception e) {
            log.warn("[Notification] altIds 캐시 저장 실패: {}", e.getMessage());
        }

        log.info("[Notification] 실시간 대안 탐색 완료 — notificationId={}, 좌표=({},{}), {}개",
                n.getId(), n.getOriginalLat(), n.getOriginalLng(), dtos.size());
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
    public String replacePlan(Long notificationId, Long newPlaceId, String userEmail) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));

        validateOwner(notification.getUserId(), userEmail);

        // 대안 목록 검증
        List<Long> altIds = parseAltIds(notification.getAlternativePlaceIds());
        if (!altIds.contains(newPlaceId)) {
            throw new IllegalArgumentException("선택한 장소가 이 알림의 대안 목록에 없습니다.");
        }

        // 1) + 2) TripPlace 교체
        TripPlace tripPlace = tripPlaceRepository.findById(notification.getPlanId())
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없습니다."));

        Place newPlace = placeRepository.findById(newPlaceId)
                .orElseThrow(() -> new IllegalArgumentException("장소를 찾을 수 없습니다."));

        tripPlace.replace(newPlace.getGooglePlaceId(), newPlace.getName());
        tripPlaceRepository.save(tripPlace);

        // 3) 알림 읽음 처리
        notification.setRead(true);
        notificationRepository.save(notification);

        // 4) 피드백: 선택 +1.0 / 나머지 -0.3
        preferenceService.applyFeedback(notification.getUserId(), altIds, newPlaceId);

        log.info("[Notification] 일정 교체 완료 — planId={}, newPlaceId={}", notification.getPlanId(), newPlaceId);
        return "일정이 교체되었습니다. (planId=" + notification.getPlanId() + ", newPlaceId=" + newPlaceId + ")";
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
