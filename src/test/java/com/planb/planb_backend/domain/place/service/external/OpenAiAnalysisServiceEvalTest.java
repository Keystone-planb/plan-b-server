package com.planb.planb_backend.domain.place.service.external;

import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.Space;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Eval 하네스] 골든셋 기준 AI 분류 정확도 회귀 테스트.
 *
 * 실제 OpenAI API를 호출하므로 비용이 발생 — 기본 `test` task에서는 제외되고
 * `./gradlew evalTest` (OPENAI_API_KEY 필요)로만 수동 실행된다.
 * 프롬프트를 수정했을 때 분류 품질이 떨어지는지 여기서 회귀로 잡아낸다.
 *
 * space/type은 리뷰 문맥상 정답이 비교적 명확해 회귀 게이트(임계값 미달 시 실패)로 쓰고,
 * mood는 주관적 판단 영역이라 게이트로 쓰지 않고 로그로만 추적한다.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@ExtendWith(MockitoExtension.class)
class OpenAiAnalysisServiceEvalTest {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAnalysisServiceEvalTest.class);
    private static final double MIN_ACCURACY = 0.7;

    @Mock
    private AiCallLogService aiCallLogService;

    private record GoldenCase(String placeName, String category, Map<String, List<String>> reviews,
                               Space expectedSpace, PlaceType expectedType, Mood expectedMood) {
    }

    private static List<GoldenCase> goldenSet() {
        return List.of(
                new GoldenCase("할머니 손칼국수", "restaurant",
                        Map.of("Google", List.of(
                                "50년 전통 손칼국수집. 좌석 협소하지만 줄서서 먹는 맛집.",
                                "옛날 그 맛 그대로, 어르신들 단골 많음")),
                        Space.INDOOR, PlaceType.FOOD, Mood.LOCAL),
                new GoldenCase("한강 스카이 루프탑 카페", "cafe",
                        Map.of("Google", List.of(
                                "한강뷰 루프탑, 사진 찍으러 많이 옴. 감성 인테리어",
                                "노을 질 때 인스타 감성 최고")),
                        Space.MIX, PlaceType.CAFE, Mood.TRENDY),
                new GoldenCase("북한산 둘레길 전망대", "tourist_attraction",
                        Map.of("Google", List.of(
                                "등산로 따라 올라가면 서울 전경이 한눈에 보임. 공기 좋고 조용함")),
                        Space.OUTDOOR, PlaceType.SIGHTS, Mood.HEALING),
                new GoldenCase("현대백화점 무역센터점", "department_store",
                        Map.of("Google", List.of(
                                "다양한 브랜드 입점, 주차 편리, 쇼핑하기 좋음")),
                        Space.INDOOR, PlaceType.SHOP, Mood.TRENDY),
                new GoldenCase("망원시장", "market",
                        Map.of("Google", List.of(
                                "전통시장 특유의 활기, 먹거리 골목 유명, 현지인도 많이 옴")),
                        Space.OUTDOOR, PlaceType.MARKET, Mood.LOCAL),
                new GoldenCase("롯데월드 어드벤처", "amusement_park",
                        Map.of("Google", List.of(
                                "실내외 놀이기구 다 있음, 아이들이랑 가기 좋고 스릴 넘침")),
                        Space.MIX, PlaceType.THEME, Mood.ACTIVE),
                new GoldenCase("국립중앙박물관", "museum",
                        Map.of("Google", List.of(
                                "유물 전시 규모가 크고 잘 정리되어 있음, 역사 공부하기 좋음")),
                        Space.INDOOR, PlaceType.CULTURE, Mood.CLASSIC),
                new GoldenCase("서울숲", "park",
                        Map.of("Google", List.of(
                                "넓은 잔디밭과 산책로, 사슴도 볼 수 있고 가족 나들이 명소")),
                        Space.OUTDOOR, PlaceType.PARK, Mood.HEALING),
                new GoldenCase("이태원 포차거리", "bar",
                        Map.of("Google", List.of(
                                "밤늦게까지 술 마시며 놀기 좋은 포차거리, 활기찬 분위기")),
                        Space.MIX, PlaceType.FOOD, Mood.ACTIVE),
                new GoldenCase("교보문고 광화문점", "book_store",
                        Map.of("Google", List.of(
                                "책 종류 많고 조용히 앉아서 읽을 공간도 있음")),
                        Space.INDOOR, PlaceType.SHOP, Mood.HEALING)
        );
    }

    @Test
    @DisplayName("골든셋 10건 기준 space/type 분류 정확도가 임계값(70%) 이상")
    void goldenSetAccuracy_meetsThreshold() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                apiKey, "https://api.openai.com/v1", aiCallLogService);

        List<GoldenCase> cases = goldenSet();
        int spaceCorrect = 0, typeCorrect = 0, moodCorrect = 0;

        for (GoldenCase c : cases) {
            Map<String, Object> result = service.requestAnalysis(c.placeName(), c.category(), c.reviews());

            String actualSpace = String.valueOf(result.get("space"));
            String actualType = String.valueOf(result.get("type"));
            String actualMood = String.valueOf(result.get("mood"));

            boolean spaceMatch = c.expectedSpace().name().equalsIgnoreCase(actualSpace);
            boolean typeMatch = c.expectedType().name().equalsIgnoreCase(actualType);
            boolean moodMatch = c.expectedMood().name().equalsIgnoreCase(actualMood);

            if (spaceMatch) spaceCorrect++;
            if (typeMatch) typeCorrect++;
            if (moodMatch) moodCorrect++;

            log.info("[EVAL] {} → space={}(기대 {}, {}) type={}(기대 {}, {}) mood={}(기대 {}, {})",
                    c.placeName(),
                    actualSpace, c.expectedSpace(), spaceMatch ? "O" : "X",
                    actualType, c.expectedType(), typeMatch ? "O" : "X",
                    actualMood, c.expectedMood(), moodMatch ? "O" : "X");
        }

        double spaceAccuracy = (double) spaceCorrect / cases.size();
        double typeAccuracy = (double) typeCorrect / cases.size();
        double moodAccuracy = (double) moodCorrect / cases.size();

        log.info("[EVAL] 정확도 — space: {}%, type: {}%, mood(참고용, 게이트 아님): {}%",
                Math.round(spaceAccuracy * 100), Math.round(typeAccuracy * 100), Math.round(moodAccuracy * 100));

        assertThat(spaceAccuracy).isGreaterThanOrEqualTo(MIN_ACCURACY);
        assertThat(typeAccuracy).isGreaterThanOrEqualTo(MIN_ACCURACY);
    }
}
