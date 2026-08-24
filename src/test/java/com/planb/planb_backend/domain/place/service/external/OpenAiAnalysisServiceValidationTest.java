package com.planb.planb_backend.domain.place.service.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [출력 검증] OpenAiAnalysisService.validate() 가 스키마 위반(enum 오류, 필수 필드 누락)을
 * 정확히 잡아내는지 확인하는 단위 테스트. 네트워크 호출 없이 검증 로직만 테스트한다.
 * (self-repair 재질의 자체의 통합 동작은 실제 API를 쓰는 OpenAiAnalysisServiceEvalTest 쪽에서 간접 검증됨)
 */
@ExtendWith(MockitoExtension.class)
class OpenAiAnalysisServiceValidationTest {

    @Mock
    private AiCallLogService aiCallLogService;

    private final OpenAiAnalysisService service =
            new OpenAiAnalysisService("dummy-key", "https://api.openai.com/v1", aiCallLogService);

    @Test
    @DisplayName("모든 필드가 스키마를 지키면 에러 없음")
    void validate_validResponse_noErrors() {
        Map<String, Object> valid = Map.of(
                "space", "INDOOR",
                "type", "CAFE",
                "mood", "TRENDY",
                "review_data", "감성 카페",
                "summaries", Map.of("Google", "좋음")
        );

        assertThat(service.validate(valid)).isEmpty();
    }

    @Test
    @DisplayName("space가 복합값(INDOOR | OUTDOOR)이면 에러 — 단일 enum만 허용")
    void validate_compoundSpace_returnsError() {
        Map<String, Object> invalid = Map.of(
                "space", "INDOOR | OUTDOOR",
                "type", "CAFE",
                "mood", "TRENDY",
                "review_data", "감성 카페",
                "summaries", Map.of("Google", "좋음")
        );

        assertThat(service.validate(invalid)).anyMatch(e -> e.contains("space"));
    }

    @Test
    @DisplayName("type이 스키마에 없는 값(BAR)이면 에러")
    void validate_unknownType_returnsError() {
        Map<String, Object> invalid = Map.of(
                "space", "MIX",
                "type", "BAR",
                "mood", "ACTIVE",
                "review_data", "포차거리",
                "summaries", Map.of("Google", "좋음")
        );

        assertThat(service.validate(invalid)).anyMatch(e -> e.contains("type"));
    }

    @Test
    @DisplayName("review_data 필드가 없으면 에러")
    void validate_missingReviewData_returnsError() {
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("space", "INDOOR");
        invalid.put("type", "FOOD");
        invalid.put("mood", "LOCAL");
        invalid.put("summaries", Map.of("Google", "좋음"));

        assertThat(service.validate(invalid)).anyMatch(e -> e.contains("review_data"));
    }

    @Test
    @DisplayName("summaries 필드가 없으면 에러")
    void validate_missingSummaries_returnsError() {
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("space", "INDOOR");
        invalid.put("type", "FOOD");
        invalid.put("mood", "LOCAL");
        invalid.put("review_data", "요약");

        assertThat(service.validate(invalid)).anyMatch(e -> e.contains("summaries"));
    }

    @Test
    @DisplayName("응답 자체가 null이면 에러")
    void validate_nullResponse_returnsError() {
        assertThat(service.validate(null)).isNotEmpty();
    }
}
