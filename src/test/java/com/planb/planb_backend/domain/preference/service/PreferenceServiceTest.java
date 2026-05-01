package com.planb.planb_backend.domain.preference.service;

import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.preference.dto.PreferenceSummaryResponse;
import com.planb.planb_backend.domain.preference.entity.UserPreference;
import com.planb.planb_backend.domain.preference.repository.UserPreferenceRepository;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import org.springframework.test.util.ReflectionTestUtils;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PreferenceServiceTest {

    @Mock private UserPreferenceRepository userPreferenceRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private PreferenceService preferenceService;

    // ──────────────────────────────────────────────────────
    // applyFeedback 테스트
    // ──────────────────────────────────────────────────────

    @Test
    @DisplayName("선택된 장소 mood +1.0 / 미선택 장소 mood -0.3 적용")
    void applyFeedback_deltaCorrect() {
        Long userId = 1L;

        // 노출 장소 3개 (HEALING, ACTIVE, TRENDY)
        Place healingPlace = placeWithMood(10L, Mood.HEALING);
        Place activePlace  = placeWithMood(20L, Mood.ACTIVE);
        Place trendyPlace  = placeWithMood(30L, Mood.TRENDY);

        when(placeRepository.findById(10L)).thenReturn(Optional.of(healingPlace));
        when(placeRepository.findById(20L)).thenReturn(Optional.of(activePlace));
        when(placeRepository.findById(30L)).thenReturn(Optional.of(trendyPlace));

        // 기존 이력 없음
        when(userPreferenceRepository.findByUserIdAndMood(any(), any()))
                .thenReturn(Optional.empty());

        // 20번(ACTIVE) 선택
        preferenceService.applyFeedback(userId, List.of(10L, 20L, 30L), 20L);

        // save가 3회 호출됐는지 확인
        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository, times(3)).save(captor.capture());

        List<UserPreference> saved = captor.getAllValues();

        // HEALING (-0.3), ACTIVE (+1.0), TRENDY (-0.3)
        UserPreference healingPref = saved.stream().filter(p -> p.getMood() == Mood.HEALING).findFirst().orElseThrow();
        UserPreference activePref  = saved.stream().filter(p -> p.getMood() == Mood.ACTIVE).findFirst().orElseThrow();
        UserPreference trendyPref  = saved.stream().filter(p -> p.getMood() == Mood.TRENDY).findFirst().orElseThrow();

        assertThat(healingPref.getScore()).isEqualTo(-0.3);
        assertThat(activePref.getScore()).isEqualTo(1.0);
        assertThat(trendyPref.getScore()).isEqualTo(-0.3);
    }

    @Test
    @DisplayName("mood가 null인 장소는 피드백 스킵 (save 미호출)")
    void applyFeedback_nullMoodPlace_skipped() {
        Place noMoodPlace = new Place();
        noMoodPlace.setMood(null);

        when(placeRepository.findById(99L)).thenReturn(Optional.of(noMoodPlace));

        preferenceService.applyFeedback(1L, List.of(99L), 99L);

        verify(userPreferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 placeId는 조용히 스킵")
    void applyFeedback_unknownPlace_skipped() {
        when(placeRepository.findById(999L)).thenReturn(Optional.empty());

        preferenceService.applyFeedback(1L, List.of(999L), 999L);

        verify(userPreferenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존 이력이 있으면 점수 누적 (초기화 아님)")
    void applyFeedback_existingPref_accumulated() {
        UserPreference existing = new UserPreference(1L, Mood.HEALING);
        existing.setScore(3.0); // 기존 점수

        Place healingPlace = placeWithMood(10L, Mood.HEALING);
        when(placeRepository.findById(10L)).thenReturn(Optional.of(healingPlace));
        when(userPreferenceRepository.findByUserIdAndMood(1L, Mood.HEALING))
                .thenReturn(Optional.of(existing));

        // 선택됨 → +1.0
        preferenceService.applyFeedback(1L, List.of(10L), 10L);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).save(captor.capture());

        // 3.0 + 1.0 = 4.0
        assertThat(captor.getValue().getScore()).isEqualTo(4.0);
    }

    // ──────────────────────────────────────────────────────
    // getSummary 테스트
    // ──────────────────────────────────────────────────────

    @Test
    @DisplayName("누적 점수 합이 1.0 미만이면 hasEnoughData=false, message=null")
    void getSummary_notEnoughData() {
        UserPreference pref = new UserPreference(1L, Mood.HEALING);
        pref.setScore(0.7);  // abs 합 0.7 < 1.0

        when(userPreferenceRepository.findByUserId(1L)).thenReturn(List.of(pref));

        PreferenceSummaryResponse response = preferenceService.getSummary(1L);

        assertThat(response.isHasEnoughData()).isFalse();
        assertThat(response.getMessage()).isNull();
    }

    @Test
    @DisplayName("누적 점수 합 ≥ 1.0이면 가장 높은 mood 기반 요약 반환")
    void getSummary_enoughData_topMoodMessage() {
        UserPreference healingPref = new UserPreference(1L, Mood.HEALING);
        healingPref.setScore(3.0);

        UserPreference activePref = new UserPreference(1L, Mood.ACTIVE);
        activePref.setScore(1.0);

        when(userPreferenceRepository.findByUserId(1L))
                .thenReturn(List.of(healingPref, activePref));

        User user = User.builder()
                .email("jimin@planb.com")
                .nickname("지민")
                .provider("local")
                .role(Role.USER)
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        PreferenceSummaryResponse response = preferenceService.getSummary(1L);

        assertThat(response.isHasEnoughData()).isTrue();
        // HEALING이 최고점 → 힐링 메시지
        assertThat(response.getMessage())
                .contains("지민님은 보통")
                .contains("힐링되는 조용한 곳");
    }

    @Test
    @DisplayName("모든 Mood 한국어 매핑 확인")
    void getSummary_allMoods_koreanMessage() {
        for (Mood mood : Mood.values()) {
            UserPreference pref = new UserPreference(1L, mood);
            pref.setScore(2.0);

            when(userPreferenceRepository.findByUserId(1L)).thenReturn(List.of(pref));

            User user = User.builder()
                    .email("test@planb.com")
                    .nickname("테스트")
                    .provider("local")
                    .role(Role.USER)
                    .build();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            PreferenceSummaryResponse response = preferenceService.getSummary(1L);

            assertThat(response.isHasEnoughData()).isTrue();
            assertThat(response.getMessage()).isNotNull().isNotBlank();
        }
    }

    @Test
    @DisplayName("userId에 해당하는 유저가 없으면 닉네임 '사용자'로 fallback")
    void getSummary_userNotFound_fallbackNickname() {
        UserPreference pref = new UserPreference(1L, Mood.TRENDY);
        pref.setScore(2.0);

        when(userPreferenceRepository.findByUserId(1L)).thenReturn(List.of(pref));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        PreferenceSummaryResponse response = preferenceService.getSummary(1L);

        assertThat(response.getMessage()).startsWith("사용자님은 보통");
    }

    // ──────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────

    private Place placeWithMood(Long id, Mood mood) {
        Place p = new Place();
        p.setName("테스트 장소 " + id);
        p.setMood(mood);
        return p;
    }
}
