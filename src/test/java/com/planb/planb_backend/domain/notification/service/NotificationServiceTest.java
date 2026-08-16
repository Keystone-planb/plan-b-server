package com.planb.planb_backend.domain.notification.service;

import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
import com.planb.planb_backend.domain.recommendation.dto.UnifiedReplaceResponse;
import com.planb.planb_backend.domain.trip.entity.Itinerary;
import com.planb.planb_backend.domain.trip.entity.Trip;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private TripPlaceRepository    tripPlaceRepository;
    @Mock private PlaceRepository        placeRepository;
    @Mock private UserRepository         userRepository;
    @Mock private PreferenceService      preferenceService;

    @InjectMocks
    private NotificationService notificationService;

    private Notification notification;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .email("test@planb.com")
                .nickname("테스터")
                .provider("local")
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        notification = new Notification();
        notification.setId(100L);
        notification.setUserId(1L);
        notification.setPlanId(200L);
        notification.setAlternativePlaceIds("[301, 302, 303]");
        notification.setRead(false);
    }

    // ──────────────────────────────────────────────────────
    // replacePlan 테스트
    // ──────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 교체 — TripPlace 장소 변경 + 알림 읽음 + 피드백 적용")
    void replacePlan_success() {
        Place newPlace = new Place();
        newPlace.setGooglePlaceId("ChIJnewplace");
        newPlace.setName("새 카페");

        TripPlace tripPlace = TripPlace.builder()
                .tripPlaceId(200L)
                .placeId("ChIJoldplace")
                .name("기존 카페")
                .build();

        when(notificationRepository.findById(100L)).thenReturn(Optional.of(notification));
        when(userRepository.findByEmail("test@planb.com")).thenReturn(Optional.of(user));
        when(tripPlaceRepository.findById(200L)).thenReturn(Optional.of(tripPlace));
        when(placeRepository.findById(301L)).thenReturn(Optional.of(newPlace));
        when(tripPlaceRepository.save(any())).thenReturn(tripPlace);

        UnifiedReplaceResponse result = notificationService.replacePlan(100L, 301L, "test@planb.com");

        // 응답 확인 (null 아닌 것만 검증 — 메시지 필드 제거됨)
        assertThat(result).isNotNull();

        // TripPlace 교체 확인
        assertThat(tripPlace.getName()).isEqualTo("[새 카페] (PLAN B)");
        assertThat(tripPlace.getPlaceId()).isEqualTo("ChIJnewplace");

        // 알림 읽음 처리 확인
        assertThat(notification.isRead()).isTrue();

        // 피드백 적용 확인
        verify(preferenceService).applyFeedback(eq(1L), any(), eq(301L));
    }

    @Test
    @DisplayName("newPlaceId가 대안 목록에 없으면 400 (IllegalArgumentException)")
    void replacePlan_invalidPlaceId_throwsException() {
        when(notificationRepository.findById(100L)).thenReturn(Optional.of(notification));
        when(userRepository.findByEmail("test@planb.com")).thenReturn(Optional.of(user));

        // 999는 대안 목록 [301, 302, 303]에 없음
        assertThatThrownBy(() ->
                notificationService.replacePlan(100L, 999L, "test@planb.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대안 목록에 없습니다");
    }

    @Test
    @DisplayName("존재하지 않는 알림 ID → IllegalArgumentException")
    void replacePlan_notificationNotFound() {
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                notificationService.replacePlan(999L, 301L, "test@planb.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알림을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("다른 사용자 알림 접근 시 SecurityException (소유권 검증)")
    void replacePlan_wrongOwner_throwsSecurityException() {
        User otherUser = User.builder()
                .email("other@planb.com")
                .nickname("타인")
                .provider("local")
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(otherUser, "id", 99L);  // notification.userId=1 과 다름

        when(notificationRepository.findById(100L)).thenReturn(Optional.of(notification));
        when(userRepository.findByEmail("other@planb.com")).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() ->
                notificationService.replacePlan(100L, 301L, "other@planb.com"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("본인의 알림만 접근");
    }

    // ──────────────────────────────────────────────────────
    // getUnreadNotifications 테스트
    // 2026-08-16 부하테스트에서 발견한 N+1 회귀 방지용 — 알림 개수만큼
    // findItineraryDateById/findByIdWithItineraryAndTrip를 반복 호출하던 걸
    // findAllByIdInWithItineraryAndTrip 배치조회 1회로 바꿨음
    // ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getUnreadNotifications: 알림이 여러 개여도 TripPlace 배치조회는 정확히 1번만 호출됨")
    void getUnreadNotifications_batchesTripPlaceLookup() {
        Trip trip = Trip.builder().tripId(1L).title("여행").user(user).build();

        Notification activeNoti = notificationOf(101L, 1L, 501L);
        Notification expiredNoti = notificationOf(102L, 1L, 502L);

        when(notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(activeNoti, expiredNoti));

        TripPlace activeTp = tripPlaceWithDate(501L, trip, LocalDate.now().plusDays(1));
        TripPlace expiredTp = tripPlaceWithDate(502L, trip, LocalDate.now().minusDays(1));
        when(tripPlaceRepository.findAllByIdInWithItineraryAndTrip(anyList()))
                .thenReturn(List.of(activeTp, expiredTp));

        when(placeRepository.findByGooglePlaceId(any())).thenReturn(Optional.empty());

        List<com.planb.planb_backend.domain.notification.dto.NotificationResponse> result =
                notificationService.getUnreadNotifications(1L);

        // 만료된 알림(502)은 자동 읽음 처리돼서 결과에서 빠지고, 활성 알림(501)만 남음
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(101L);

        // 알림이 2개였지만 TripPlace 조회는 배치로 딱 1번 — 이게 깨지면 N+1이 부활한 것
        verify(tripPlaceRepository, times(1)).findAllByIdInWithItineraryAndTrip(anyList());
        // 예전에 알림마다 개별 호출하던 메서드는 이제 아예 호출 안 되어야 함
        verify(tripPlaceRepository, never()).findItineraryDateById(any());

        // 만료된 알림은 자동 읽음 처리됨
        assertThat(expiredNoti.isRead()).isTrue();
        assertThat(activeNoti.isRead()).isFalse();
    }

    private Notification notificationOf(Long id, Long userId, Long planId) {
        Notification n = new Notification();
        n.setId(id);
        n.setUserId(userId);
        n.setPlanId(planId);
        n.setAlternativePlaceIds("[]");
        n.setRead(false);
        return n;
    }

    private TripPlace tripPlaceWithDate(Long tripPlaceId, Trip trip, LocalDate date) {
        Itinerary itinerary = Itinerary.builder().trip(trip).day(1).date(date).build();
        return TripPlace.builder()
                .tripPlaceId(tripPlaceId)
                .itinerary(itinerary)
                .placeId("ChIJsome")
                .name("장소")
                .build();
    }

    // ──────────────────────────────────────────────────────
    // dismiss 테스트
    // ──────────────────────────────────────────────────────

    @Test
    @DisplayName("dismiss — isRead=true 변경, 일정은 그대로")
    void dismiss_success() {
        when(notificationRepository.findById(100L)).thenReturn(Optional.of(notification));
        when(userRepository.findByEmail("test@planb.com")).thenReturn(Optional.of(user));

        notificationService.dismiss(100L, "test@planb.com");

        assertThat(notification.isRead()).isTrue();
        // TripPlace 변경 없음
        verifyNoInteractions(tripPlaceRepository);
        // 피드백 없음
        verifyNoInteractions(preferenceService);
    }

    @Test
    @DisplayName("dismiss — 다른 사용자 시도 시 SecurityException")
    void dismiss_wrongOwner_throwsSecurityException() {
        User otherUser = User.builder()
                .email("other@planb.com")
                .nickname("타인")
                .provider("local")
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(otherUser, "id", 99L);

        when(notificationRepository.findById(100L)).thenReturn(Optional.of(notification));
        when(userRepository.findByEmail("other@planb.com")).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() ->
                notificationService.dismiss(100L, "other@planb.com"))
                .isInstanceOf(SecurityException.class);
    }
}
