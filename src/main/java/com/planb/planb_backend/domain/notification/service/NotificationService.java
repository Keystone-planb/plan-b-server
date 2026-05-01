package com.planb.planb_backend.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.notification.dto.AlternativePlaceDto;
import com.planb.planb_backend.domain.notification.dto.NotificationResponse;
import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ───────────────────────────────────────────
    //  GET /api/notifications/{userId}
    // ───────────────────────────────────────────

    /**
     * 미확인 알림 + 대안 카드 목록 조회
     */
    public List<NotificationResponse> getUnreadNotifications(Long userId) {
        return notificationRepository
                .findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private NotificationResponse toResponse(Notification n) {
        List<Long> altIds = parseAltIds(n.getAlternativePlaceIds());
        List<AlternativePlaceDto> alternatives = altIds.stream()
                .map(id -> placeRepository.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(AlternativePlaceDto::from)
                .collect(Collectors.toList());

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
            log.warn("[Notification] alternativePlaceIds 파싱 실패: {}", json);
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
