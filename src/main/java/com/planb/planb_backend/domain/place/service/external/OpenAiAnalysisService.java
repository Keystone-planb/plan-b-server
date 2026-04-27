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
     * 반환: space, type, mood, review_data, summaries(플랫폼별)
     */
    public Map<String, Object> requestAnalysis(String placeName, String category, Map<String, List<String>> reviews) {
        String prompt = String.format("""
            너는 'PLAN B' 서비스의 전문 여행 데이터 분석가야.
            제공된 플랫폼별 리뷰 데이터를 분석해서 장소('%s', 카테고리: '%s')의 특징을 요약해줘.

            ### [필독] 분석 및 응답 규칙 ###
            1. **데이터 기반 요약**: 'summaries' 필드를 작성할 때, 반드시 해당 플랫폼의 리뷰 리스트에 내용이 있을 때만 요약해라.
            2. **가짜 답변 생성 금지**: 리뷰 리스트가 ["데이터 없음"] 이거나 비어 있다면, 절대 소설을 쓰지 마라. 반드시 "데이터 부족으로 분석 불가"라고만 적어라.
            3. **태그 결정**: space, type, mood는 데이터가 있는 플랫폼들의 통합 의견으로 결정하되, 모두 "데이터 없음"이라면 장소 이름과 카테고리에 기반해 추론해라.
            4. **총평(review_data)**: 장소 전체를 아우르는 핵심 요약을 50자 이내의 한국어 문장으로 작성해라.
            5. **언어**: 모든 응답 내용은 한국어로 작성해라.

            ### 응답 형식 (JSON) ###
            {
              "space": "INDOOR | OUTDOOR | MIX",
              "type": "FOOD | CAFE | SIGHTS | SHOP | MARKET | THEME | CULTURE | PARK",
              "mood": "HEALING | ACTIVE | TRENDY | CLASSIC | LOCAL",
              "review_data": "장소에 대한 최종 요약 한 줄",
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
