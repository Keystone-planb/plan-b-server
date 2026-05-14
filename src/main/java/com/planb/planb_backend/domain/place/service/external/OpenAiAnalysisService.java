package com.planb.planb_backend.domain.place.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAiAnalysisService {

    @Value("${openai.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.openai.com/v1")
            .build();

    /**
     * GPT-4o-mini로 장소 리뷰 분석 요청
     * 반환: space, type, mood, review_data,summaries(플랫폼별)
     */
    public Map<String, Object> requestAnalysis(String placeName, String category, Map<String, List<String>> reviews) {
        String prompt = String.format("""
            너는 여행 대체 일정 추천 서비스 'PLAN B'의 전문 데이터 분석가다.
            제공된 플랫폼별 리뷰 데이터를 분석하여 장소('%s', 카테고리: '%s')의 특징을 요약하고 아래 JSON 형식에 맞춰 응답하라.

            [필독] 분석 및 응답 규칙
            데이터 기반 요약 (summaries):
            'summaries' 내 각 플랫폼(Google, Instagram, Naver) 필드는 해당 플랫폼의 리뷰 리스트에 구체적인 내용이 있을 때만 요약한다.
            리뷰 리스트가 ["데이터 없음"] 이거나 비어 있다면, 절대 내용을 조작하거나 생성하지 마라. 반드시 "데이터 부족으로 분석 불가"라고 작성한다.

            태그 결정 (space, type, mood) - 반드시 하나만 선택:
            데이터가 존재하는 플랫폼들의 통합 의견을 바탕으로 아래 제공된 옵션 중 단 '하나'만 선택한다.
            만약 모든 플랫폼이 "데이터 없음"이라면, 장소 이름과 카테고리를 기반으로 가장 적합한 항목을 추론하여 결정한다.
            [옵션 리스트]:
            space: [INDOOR, OUTDOOR, MIX] 중 택 1
            type: [FOOD, CAFE, SIGHTS, SHOP, MARKET, THEME, CULTURE, PARK] 중 택 1
            mood: [HEALING, ACTIVE, TRENDY, CLASSIC, LOCAL] 중 택 1

            총평 (review_data):
            데이터가 있는 경우: 장소 전체를 아우르는 핵심 특징을 50자 이내의 한국어 문장으로 작성한다.
            데이터가 없는 경우: "정보가 부족하여 상세 특징을 파악하기 어렵습니다."라고 작성한다.

            언어 및 형식:
            모든 응답 내용은 한국어로 작성하며, 반드시 유효한 JSON 형식만 출력한다.

            응답 형식 (JSON)
            {
              "space": "하나의 값만 선택",
              "type": "하나의 값만 선택",
              "mood": "하나의 값만 선택",
              "review_data": "50자 이내 요약 문장",
              "summaries": {
                "Google": "요약 또는 '데이터 부족으로 분석 불가'",
                "Instagram": "요약 또는 '데이터 부족으로 분석 불가'",
                "Naver": "요약 또는 '데이터 부족으로 분석 불가'"
              }
            }

            ### 입력 데이터 ###
            %s
            """, placeName, category, reviews.toString());

        try {
            Map<String, Object> response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(Map.of(
                            "model", "gpt-4o-mini",
                            "messages", List.of(
                                    Map.of("role", "system", "content", "You are a factual travel data analyst. Never invent information not present in the source data."),
                                    Map.of("role", "user", "content", prompt)),
                            "response_format", Map.of("type", "json_object")
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
                return objectMapper.readValue(content, Map.class);
            }
        } catch (Exception e) {
            log.error("AI 분석 실패 (Place: {}): {}", placeName, e.getMessage());
        }

        return Map.of(
                "space", "INDOOR",
                "type", "FOOD",
                "mood", "LOCAL",
                "review_data", "분석된 리뷰 정보가 없습니다.",
                "summaries", Map.of(
                        "Google", "데이터 부족으로 분석 불가",
                        "Instagram", "데이터 부족으로 분석 불가",
                        "Naver", "데이터 부족으로 분석 불가"
                )
        );
    }
}
