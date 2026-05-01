package com.planb.planb_backend.domain.notification.service;

import com.planb.planb_backend.domain.notification.entity.Notification;
import com.planb.planb_backend.domain.notification.repository.NotificationRepository;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.service.PreferenceService;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

        String result = notificationService.replacePlan(100L, 301L, "test@planb.com");

        // 응답 메시지 확인
        assertThat(result).contains("일정이 교체되었습니다");

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
