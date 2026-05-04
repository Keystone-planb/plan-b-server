package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.place.dto.UserContext;
import com.planb.planb_backend.domain.place.entity.BusinessStatus;
import com.planb.planb_backend.domain.place.entity.Place;
import com.planb.planb_backend.domain.place.repository.PlaceRepository;
import com.planb.planb_backend.domain.recommendation.dto.PlaceResult;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationStreamTest {

    @Mock private GooglePlaceApiService googlePlaceApiService;
    @Mock private PlaceRepository placeRepository;
    @Mock private TripPlaceRepository tripPlaceRepository;
    @Mock private ScoringStrategy scoringStrategy;
    @Mock private PlaceAnalysisService placeAnalysisService;
    @Mock private CongestionService congestionService;

    @InjectMocks
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        // 동기 Executor로 교체 → CompletableFuture가 현재 스레드에서 즉시 실행
        ReflectionTestUtils.setField(service, "analysisExecutor", (Executor) Runnable::run);

        // 공통 스텁
        lenient().when(tripPlaceRepository.findByTripId(any())).thenReturn(Collections.emptyList());
        lenient().when(congestionService.isCongested(anyString())).thenReturn(false);
        lenient().when(scoringStrategy.haversine(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(0.5); // 0.5km → distancePenalty = 0.9
        lenient().when(scoringStrategy.calculateScore(any(), any())).thenReturn(100.0);
    }

    // ────────────────────────────────────────────────────────────────
    //  테스트 1: 정상 흐름 — 이벤트 순서 확인
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 흐름: progress → place → done 순서, completeCalled=true")
    void normalFlow_eventsInOrder() throws Exception {
        // Given
        Place savedPlace = makeSavedPlace("ChIJtest1", "테스트 카페", 37.57, 126.97, 4.5, 1000);
        setupGoogleAndRepo(savedPlace);

        Place analyzedPlace = copyPlace(savedPlace);
        when(placeAnalysisService.processPlaceAnalysis(1L)).thenReturn(analyzedPlace);

        UserContext ctx = UserContext.builder()
                .currentLat(37.5700)
                .currentLng(126.9700)
                .radiusMinute(20)
                .transportMode(null)
                .keepOriginalCategory(true)
                .considerNextPlan(false)
                .build();

        RecordingEmitter emitter = new RecordingEmitter();

        // When
        service.doStreamAsync(ctx, emitter);

        // Then
        assertThat(emitter.events).containsExactly("progress", "place", "done");
        assertThat(emitter.dataItems.get(0)).isInstanceOf(Map.class);
        assertThat(emitter.dataItems.get(1)).isInstanceOf(PlaceResult.class);
        assertThat(emitter.dataItems.get(2)).isEqualTo("[DONE]");
        assertThat(emitter.completeCalled).isTrue();
    }

    // ────────────────────────────────────────────────────────────────
    //  테스트 2: 최대 5개 제한 — 후보 7개에서 정확히 5개만 전송
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("최대 5개 제한: 후보 7개가 있어도 place 이벤트는 5번만 전송")
    void maxFive_sevenCandidates_onlyFiveEmitted() throws Exception {
        // Given: Google에서 7개 장소 반환
        List<Map<String, Object>> googleResults = new ArrayList<>();
        List<Place> savedPlaces = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String gId = "ChIJtest" + i;
            Place p = makeSavedPlace(gId, "카페" + i, 37.57 + i * 0.001, 126.97, 4.0, 500);
            savedPlaces.add(p);
            googleResults.add(makeGoogleResult(gId, "카페" + i, 37.57 + i * 0.001, 126.97, 4.0, 500));
            lenient().when(placeRepository.findByGooglePlaceId(gId)).thenReturn(Optional.empty());
        }
        lenient().when(googlePlaceApiService.searchNearbyPlaces(
                anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(googleResults);
        lenient().when(placeRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Place p = inv.getArgument(0);
            // 이름 기반으로 ID 주입
            int idx = savedPlaces.stream()
                    .filter(sp -> sp.getName().equals(p.getName()))
                    .findFirst()
                    .map(sp -> (int)(long)sp.getId())
                    .orElse(99);
            ReflectionTestUtils.setField(p, "id", (long) idx);
            return p;
        });

        // 분석은 모두 성공, 동일 place 반환
        lenient().when(placeAnalysisService.processPlaceAnalysis(any()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(0);
                    Place analyzed = new Place();
                    analyzed.setName("카페" + id);
                    analyzed.setLatitude(37.57 + id * 0.001);
                    analyzed.setLongitude(126.97);
                    analyzed.setRating(4.0);
                    analyzed.setUserRatingsTotal(500);
                    return analyzed;
                });

        UserContext ctx = UserContext.builder()
                .currentLat(37.5700)
                .currentLng(126.9700)
                .radiusMinute(20)
                .transportMode(null)
                .keepOriginalCategory(true)
                .considerNextPlan(false)
                .build();

        RecordingEmitter emitter = new RecordingEmitter();

        // When
        service.doStreamAsync(ctx, emitter);

        // Then: place 이벤트는 최대 5개
        long placeCount = emitter.events.stream().filter("place"::equals).count();
        assertThat(placeCount).isLessThanOrEqualTo(5);
        assertThat(emitter.events).contains("progress");
        assertThat(emitter.events).contains("done");
        assertThat(emitter.completeCalled).isTrue();
    }

    // ────────────────────────────────────────────────────────────────
    //  테스트 3: 분석 실패 — 원본 장소로 폴백하여 계속 전송
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("분석 실패 시 원본 장소로 폴백하여 place 이벤트 정상 전송")
    void analysisFails_fallbackPlaceStillEmitted() throws Exception {
        // Given
        Place savedPlace = makeSavedPlace("ChIJfail1", "폴백 카페", 37.57, 126.97, 4.2, 300);
        setupGoogleAndRepo(savedPlace);

        // processPlaceAnalysis 실패 → enrichBusinessInfo 경량 보강 후 원본 반환
        when(placeAnalysisService.processPlaceAnalysis(1L))
                .thenThrow(new RuntimeException("OpenAI API 오류"));
        lenient().when(googlePlaceApiService.getPlaceBusinessInfo("ChIJfail1"))
                .thenReturn(Collections.emptyMap());

        UserContext ctx = UserContext.builder()
                .currentLat(37.5700)
                .currentLng(126.9700)
                .radiusMinute(20)
                .transportMode(null)
                .keepOriginalCategory(true)
                .considerNextPlan(false)
                .build();

        RecordingEmitter emitter = new RecordingEmitter();

        // When
        service.doStreamAsync(ctx, emitter);

        // Then: 분석 실패에도 place 이벤트 전송 (원본 장소 폴백)
        assertThat(emitter.events).contains("place");
        assertThat(emitter.events).contains("done");
        assertThat(emitter.completeCalled).isTrue();
    }

    // ────────────────────────────────────────────────────────────────
    //  테스트 4: AI 카테고리 검열 — 타입 불일치 장소 제외
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AI 카테고리 필터: 분석 결과 타입 불일치 장소는 place 이벤트 미전송")
    void aiCategoryFilter_mismatch_placeNotEmitted() throws Exception {
        // Given: AI가 CAFE로 분석했지만 요청은 FOOD
        Place savedPlace = makeSavedPlace("ChIJcafe1", "스타벅스", 37.57, 126.97, 4.3, 800);
        setupGoogleAndRepo(savedPlace);

        Place analyzedPlace = copyPlace(savedPlace);
        analyzedPlace.setType(PlaceType.CAFE);  // AI가 CAFE로 판단
        when(placeAnalysisService.processPlaceAnalysis(1L)).thenReturn(analyzedPlace);

        UserContext ctx = UserContext.builder()
                .currentLat(37.5700)
                .currentLng(126.9700)
                .radiusMinute(20)
                .transportMode(null)
                .keepOriginalCategory(false)  // AI 검열 활성화
                .selectedType("FOOD")          // FOOD 요청
                .considerNextPlan(false)
                .build();

        RecordingEmitter emitter = new RecordingEmitter();

        // When
        service.doStreamAsync(ctx, emitter);

        // Then: CAFE는 FOOD 요청에서 탈락 → place 이벤트 없음
        long placeCount = emitter.events.stream().filter("place"::equals).count();
        assertThat(placeCount).isZero();
        assertThat(emitter.events).containsExactly("progress", "done");
        assertThat(emitter.completeCalled).isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    //  헬퍼 메서드
    // ════════════════════════════════════════════════════════════════

    /** 저장된 Place 객체 생성 (ID 포함) */
    private Place makeSavedPlace(String gId, String name, double lat, double lng,
                                  double rating, int reviewCount) {
        Place p = new Place();
        p.setGooglePlaceId(gId);
        p.setName(name);
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setRating(rating);
        p.setUserRatingsTotal(reviewCount);
        p.setBusinessStatus(BusinessStatus.OPERATIONAL);
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    /** Place 얕은 복사 (analyzed 반환용) */
    private Place copyPlace(Place src) {
        Place copy = new Place();
        copy.setGooglePlaceId(src.getGooglePlaceId());
        copy.setName(src.getName());
        copy.setLatitude(src.getLatitude());
        copy.setLongitude(src.getLongitude());
        copy.setRating(src.getRating());
        copy.setUserRatingsTotal(src.getUserRatingsTotal());
        copy.setBusinessStatus(src.getBusinessStatus());
        return copy;
    }

    /** Google Nearby 결과 맵 생성 */
    private Map<String, Object> makeGoogleResult(String gId, String name, double lat, double lng,
                                                   double rating, int reviews) {
        Map<String, Object> location = Map.of("lat", lat, "lng", lng);
        Map<String, Object> geometry = Map.of("location", location);
        Map<String, Object> result = new HashMap<>();
        result.put("place_id", gId);
        result.put("name", name);
        result.put("geometry", geometry);
        result.put("rating", rating);
        result.put("user_ratings_total", reviews);
        result.put("business_status", "OPERATIONAL");
        return result;
    }

    /** 단일 장소 Google / Repository 스텁 공통 설정 */
    private void setupGoogleAndRepo(Place savedPlace) {
        String gId = savedPlace.getGooglePlaceId();
        String name = savedPlace.getName();
        double lat = savedPlace.getLatitude();
        double lng = savedPlace.getLongitude();
        double rating = savedPlace.getRating();
        int reviews = savedPlace.getUserRatingsTotal();

        when(googlePlaceApiService.searchNearbyPlaces(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(makeGoogleResult(gId, name, lat, lng, rating, reviews)));
        when(placeRepository.findByGooglePlaceId(gId)).thenReturn(Optional.empty());
        when(placeRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Place p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 1L);
            return p;
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  RecordingEmitter — SSE 이벤트를 인메모리로 캡처
    // ════════════════════════════════════════════════════════════════

    /**
     * SseEmitter를 확장하여 전송된 이벤트를 인메모리에 기록.
     *
     * SseEventBuilder.build()가 반환하는 LinkedHashSet<DataWithMediaType>의 순서:
     *   "event:name\n" → "data:" → actualData → "\n"
     * 이 순서를 파싱하여 event 이름과 실제 데이터를 분리한다.
     */
    static class RecordingEmitter extends SseEmitter {

        final List<String> events = new ArrayList<>();
        final List<Object> dataItems = new ArrayList<>();
        boolean completeCalled = false;

        RecordingEmitter() { super(60_000L); }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            List<DataWithMediaType> items = new ArrayList<>(builder.build());
            // Spring 7: "event:name\n" + "data:" 가 "event:name\ndata:" 한 문자열로 합쳐져 저장됨
            String pendingEvent = null;

            for (DataWithMediaType item : items) {
                Object raw = item.getData();
                if (raw instanceof String s) {
                    if (s.contains("event:")) {
                        // "event:name\ndata:" — 이벤트 이름 추출
                        int start = s.indexOf("event:") + 6;
                        int end = s.indexOf('\n', start);
                        pendingEvent = end > start ? s.substring(start, end) : s.substring(start);
                    } else if (!s.startsWith("\n") && !s.isEmpty()) {
                        // 실제 문자열 데이터 (e.g. "[DONE]") — SSE 프로토콜 메타 문자 아님
                        if (pendingEvent != null) {
                            events.add(pendingEvent);
                            dataItems.add(raw);
                            pendingEvent = null;
                        }
                    }
                    // "\n\n" 등 SSE 프레임 종료 문자는 무시
                } else {
                    // 비문자열 객체 (PlaceResult, Map 등)
                    if (pendingEvent != null) {
                        events.add(pendingEvent);
                        dataItems.add(raw);
                        pendingEvent = null;
                    }
                }
            }
        }

        @Override
        public synchronized void complete() {
            completeCalled = true;
            // HTTP 응답이 없는 단위 테스트에서는 super.complete() 호출 안 함
        }

        @Override
        public synchronized void completeWithError(Throwable ex) {
            completeCalled = true;
        }
    }
}
