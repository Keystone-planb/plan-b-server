package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.preference.entity.UserPreference;
import com.planb.planb_backend.domain.preference.repository.UserPreferenceRepository;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.Space;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringStrategyTest {

    @Mock
    private UserPreferenceRepository userPreferenceRepository;

    @InjectMocks
    private ScoringStrategy scoringStrategy;

    private Place place;
    private UserContext baseContext;

    @BeforeEach
    void setUp() {
        // 서울 경복궁 기준 장소
        place = new Place();
        place.setName("경복궁");
        place.setLatitude(37.5796);
        place.setLongitude(126.9770);
        place.setRating(4.5);
        place.setUserRatingsTotal(10000);
        place.setMood(Mood.HEALING);
        place.setSpace(Space.OUTDOOR);

        // 경복궁 바로 근처에 위치한 사용자
        baseContext = UserContext.builder()
                .userId(1L)
                .currentLat(37.5800)
                .currentLng(126.9760)
                .radiusMinute(20)
                .walk(false)
                .considerNextPlan(false)
                .build();
    }

    @Test
    @DisplayName("Mood 선호 이력 없으면 가중치 1.0 (중립) — 점수에 영향 없음")
    void moodMultiplier_noHistory_isNeutral() {
        when(userPreferenceRepository.findByUserIdAndMood(1L, Mood.HEALING))
                .thenReturn(Optional.empty());

        double scoreWithMood = scoringStrategy.calculateScore(place, baseContext);

        // userId=null 컨텍스트(Mood 미적용)와 비교
        UserContext noUserContext = UserContext.builder()
                .userId(null)
                .currentLat(baseContext.getCurrentLat())
                .currentLng(baseContext.getCurrentLng())
                .radiusMinute(baseContext.getRadiusMinute())
                .walk(false)
                .considerNextPlan(false)
                .build();

        double scoreWithoutMood = scoringStrategy.calculateScore(place, noUserContext);

        // 이력 없으면 multiplier=1.0 → 두 점수 동일
        assertThat(scoreWithMood).isEqualTo(scoreWithoutMood);
    }

    @Test
    @DisplayName("HEALING +5점 이력 → 가중치 min(2.0, 1 + 5×0.15) = 1.75 적용")
    void moodMultiplier_positiveHistory_boosted() {
        UserPreference pref = new UserPreference(1L, Mood.HEALING);
        pref.setScore(5.0);
        when(userPreferenceRepository.findByUserIdAndMood(1L, Mood.HEALING))
                .thenReturn(Optional.of(pref));

        UserContext noUserContext = UserContext.builder()
                .userId(null)
                .currentLat(baseContext.getCurrentLat())
                .currentLng(baseContext.getCurrentLng())
                .radiusMinute(baseContext.getRadiusMinute())
                .walk(false)
                .considerNextPlan(false)
                .build();

        double baseScore = scoringStrategy.calculateScore(place, noUserContext);
        double boostedScore = scoringStrategy.calculateScore(place, baseContext);

        // 1 + 5×0.15 = 1.75
        assertThat(boostedScore).isCloseTo(baseScore * 1.75, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("HEALING -10점 이력 → 가중치 max(0.7, 1 + (-10)×0.15) = 0.7 (클램프 하한)")
    void moodMultiplier_negativeHistory_clampedToMin() {
        UserPreference pref = new UserPreference(1L, Mood.HEALING);
        pref.setScore(-10.0);
        when(userPreferenceRepository.findByUserIdAndMood(1L, Mood.HEALING))
                .thenReturn(Optional.of(pref));

        UserContext noUserContext = UserContext.builder()
                .userId(null)
                .currentLat(baseContext.getCurrentLat())
                .currentLng(baseContext.getCurrentLng())
                .radiusMinute(baseContext.getRadiusMinute())
                .walk(false)
                .considerNextPlan(false)
                .build();

        double baseScore = scoringStrategy.calculateScore(place, noUserContext);
        double penalizedScore = scoringStrategy.calculateScore(place, baseContext);

        // 클램프 하한 0.7
        assertThat(penalizedScore).isCloseTo(baseScore * 0.7, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("HEALING +100점 이력 → 가중치 최대 2.0으로 클램프")
    void moodMultiplier_veryPositiveHistory_clampedToMax() {
        UserPreference pref = new UserPreference(1L, Mood.HEALING);
        pref.setScore(100.0);
        when(userPreferenceRepository.findByUserIdAndMood(1L, Mood.HEALING))
                .thenReturn(Optional.of(pref));

        UserContext noUserContext = UserContext.builder()
                .userId(null)
                .currentLat(baseContext.getCurrentLat())
                .currentLng(baseContext.getCurrentLng())
                .radiusMinute(baseContext.getRadiusMinute())
                .walk(false)
                .considerNextPlan(false)
                .build();

        double baseScore = scoringStrategy.calculateScore(place, noUserContext);
        double boostedScore = scoringStrategy.calculateScore(place, baseContext);

        // 클램프 상한 2.0
        assertThat(boostedScore).isCloseTo(baseScore * 2.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Place.mood가 null이면 Mood 가중치 미적용 (repository 호출 없음)")
    void moodMultiplier_placeMoodIsNull_skipped() {
        place.setMood(null);

        double score = scoringStrategy.calculateScore(place, baseContext);

        // repository 호출 없어야 함
        verifyNoInteractions(userPreferenceRepository);
        assertThat(score).isGreaterThan(0);
    }

    @Test
    @DisplayName("반경 초과 장소는 거리 페널티로 점수 하락")
    void distancePenalty_outsideRadius_lowerScore() {
        // 반경 내 (가까운 장소)
        Place nearPlace = new Place();
        nearPlace.setName("광화문 카페");
        nearPlace.setLatitude(37.5800);
        nearPlace.setLongitude(126.9770);
        nearPlace.setRating(4.0);
        nearPlace.setUserRatingsTotal(500);

        // 반경 밖 (7km 떨어진 장소)
        Place farPlace = new Place();
        farPlace.setName("강남 카페");
        farPlace.setLatitude(37.5172);
        farPlace.setLongitude(127.0473);
        farPlace.setRating(4.0);
        farPlace.setUserRatingsTotal(500);

        UserContext context = UserContext.builder()
                .userId(null)
                .currentLat(37.5796)
                .currentLng(126.9770)
                .radiusMinute(10)  // 10분 차량 = 4km 반경
                .walk(false)
                .considerNextPlan(false)
                .build();

        double nearScore = scoringStrategy.calculateScore(nearPlace, context);
        double farScore = scoringStrategy.calculateScore(farPlace, context);

        assertThat(nearScore).isGreaterThan(farScore);
    }

    @Test
    @DisplayName("타원 동선 보너스 — 다음 목적지 방향 길목 장소는 2.5배 가산")
    void ellipticalBonus_onRoute_boosted() {
        // 현재 위치 → 다음 목적지 직선 사이에 놓인 장소 (detourRatio ≈ 1.0 < 1.2 → 보너스)
        // 현재: (37.5700, 126.9700), 다음: (37.5500, 126.9900)
        // 정중간: (37.5600, 126.9800) → detourRatio ≈ 0.997
        Place onRoutePlace = new Place();
        onRoutePlace.setName("경로 중간 카페");
        onRoutePlace.setLatitude(37.5600);
        onRoutePlace.setLongitude(126.9800);
        onRoutePlace.setRating(4.0);
        onRoutePlace.setUserRatingsTotal(200);

        // 경로와 무관하게 동쪽으로 크게 벗어난 장소 (detourRatio ≈ 4.5 → 보너스 없음)
        Place offRoutePlace = new Place();
        offRoutePlace.setName("우회 카페");
        offRoutePlace.setLatitude(37.5700);
        offRoutePlace.setLongitude(127.0500);
        offRoutePlace.setRating(4.0);
        offRoutePlace.setUserRatingsTotal(200);

        UserContext contextWithNext = UserContext.builder()
                .userId(null)
                .currentLat(37.5700)
                .currentLng(126.9700)
                .radiusMinute(30)
                .walk(false)
                .considerNextPlan(true)
                .nextLat(37.5500)
                .nextLng(126.9900)
                .build();

        double onRouteScore = scoringStrategy.calculateScore(onRoutePlace, contextWithNext);
        double offRouteScore = scoringStrategy.calculateScore(offRoutePlace, contextWithNext);

        assertThat(onRouteScore).isGreaterThan(offRouteScore);
    }

    @Test
    @DisplayName("haversine 공개 메서드 — 서울~부산 약 325km")
    void haversine_seoulToBusan() {
        double dist = scoringStrategy.haversine(37.5665, 126.9780, 35.1796, 129.0756);
        // 서울-부산 직선거리 약 325km (±5km 허용)
        assertThat(dist).isBetween(320.0, 330.0);
    }
}
