package com.planb.planb_backend.domain.place.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenAiAnalysisService {

    @Value("${openai.api-key:}")
    private String apiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // [커넥션 풀] 좀비 연결 방지
    // - maxIdleTime(10s): 10초 이상 유휴 연결은 즉시 제거 → OpenAI가 먼저 끊기 전에 선제 정리
    // - evictInBackground(30s): 30초마다 백그라운드에서 만료 연결 청소
    // → ECONNRESET(-104) "Connection reset by peer" 에러 원천 차단
    private static final ConnectionProvider OPENAI_POOL = ConnectionProvider.builder("openai-pool")
            .maxConnections(10)
            .maxIdleTime(Duration.ofSeconds(10))
            .evictInBackground(Duration.ofSeconds(30))
            .build();

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.openai.com/v1")
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create(OPENAI_POOL)
                            .responseTimeout(Duration.ofSeconds(20))))
            .build();

    /**
     * GPT-4o-mini로 장소 리뷰 분석 요청
     * 반환: space, type, mood, review_data, summaries(플랫폼별), reasoning(내부 사고용 — DB 저장 안 함)
     */
    public Map<String, Object> requestAnalysis(String placeName, String category, Map<String, List<String>> reviews) {

        // [A] 리뷰 데이터를 번호 목록 형식으로 구조화 (기존 reviews.toString() 대체)
        StringBuilder reviewSection = new StringBuilder();
        reviews.forEach((platform, reviewList) -> {
            boolean isEmpty = reviewList == null || reviewList.isEmpty()
                    || reviewList.contains("데이터 없음");
            reviewSection.append("[").append(platform).append(" 리뷰 — ")
                    .append(isEmpty ? 0 : reviewList.size()).append("개]\n");
            if (isEmpty) {
                reviewSection.append("(데이터 없음)\n");
            } else {
                for (int i = 0; i < reviewList.size(); i++) {
                    reviewSection.append(i + 1).append(". ").append(reviewList.get(i)).append("\n");
                }
            }
            reviewSection.append("\n");
        });

        String prompt = String.format("""
            너는 여행 대체 일정 추천 서비스 'PLAN B'의 전문 데이터 분석가다.
            아래 리뷰 데이터를 분석하여 장소의 특징을 파악하고 지정된 JSON 형식으로만 응답하라.

            [분석 순서 — 반드시 이 순서로 사고하라]
            1. 리뷰에서 장소의 핵심 특징 키워드를 추출한다.
            2. 키워드를 바탕으로 space / type / mood 를 결정한다.
            3. 결정 근거를 한 문장으로 reasoning에 작성한다.
            4. 나머지 필드를 채운다.

            [space 정의 — 반드시 하나만 선택]
            - INDOOR  : 실내 공간 (레스토랑, 카페, 쇼핑몰, 실내 전시 등)
            - OUTDOOR : 야외 공간 (공원, 자연경관, 야외 시장 등)
            - MIX     : 실내·야외 혼합 (루프탑 카페, 테마파크, 동물원 등)

            [type 정의 — 반드시 하나만 선택]
            - FOOD    : 식사 위주 음식점 (한식·중식·일식·양식·분식 등, 주문 후 착석하는 곳)
            - CAFE    : 카페·디저트 전문점 (커피, 베이커리, 아이스크림 가게 등)
            - SIGHTS  : 자연경관·랜드마크·전망대 등 '보는 것'이 목적인 장소
            - SHOP    : 쇼핑몰·백화점·아울렛·편집샵 등 쇼핑이 주목적인 매장
            - MARKET  : 전통시장·마트·슈퍼마켓 등 식재료·생필품을 파는 곳
            - THEME   : 놀이공원·워터파크·VR체험관 등 체험형 테마 시설
            - CULTURE : 박물관·미술관·갤러리·문화재·공연장
            - PARK    : 공원·산책로·자연공원·강변공원

            ※ 구분 기준:
              FOOD vs CAFE   → 식사 가능하면 FOOD, 음료·디저트 위주면 CAFE
              SIGHTS vs CULTURE → 유물·전시 있으면 CULTURE, 경관·풍경이면 SIGHTS
              MARKET vs SHOP → 생필품·식재료면 MARKET, 패션·잡화면 SHOP

            [mood 정의 — 반드시 하나만 선택]
            - HEALING : 조용하고 힐링되는 분위기 (자연, 카페, 스파 등)
            - ACTIVE  : 활동적이고 역동적인 분위기 (스포츠, 테마파크 등)
            - TRENDY  : 트렌디하고 인스타 감성의 분위기 (핫플, 감성카페 등)
            - CLASSIC : 전통·역사적 분위기 (고궁, 전통시장, 오래된 맛집 등)
            - LOCAL   : 현지인이 즐겨 찾는 로컬 감성 (동네 식당, 재래시장 등)

            [리뷰 데이터 처리 규칙]
            - 리뷰가 "(데이터 없음)"인 플랫폼의 summaries → "데이터 부족으로 분석 불가"
            - 모든 플랫폼 데이터 없음 → 장소명·카테고리 기반으로 추론
            - review_data: 50자 이내 한국어 핵심 요약
              (데이터 없으면 "정보가 부족하여 상세 특징을 파악하기 어렵습니다.")

            [응답 형식 — 반드시 유효한 JSON만 출력]
            {
              "reasoning": "type/mood 결정 근거 한 문장 (내부 사고용)",
              "space": "INDOOR | OUTDOOR | MIX 중 하나",
              "type": "FOOD | CAFE | SIGHTS | SHOP | MARKET | THEME | CULTURE | PARK 중 하나",
              "mood": "HEALING | ACTIVE | TRENDY | CLASSIC | LOCAL 중 하나",
              "review_data": "50자 이내 요약",
              "summaries": {
                "Google": "요약 또는 '데이터 부족으로 분석 불가'",
                "Naver": "요약 또는 '데이터 부족으로 분석 불가'"
              }
            }

            ### 입력 데이터 ###
            장소명: %s
            카테고리: %s

            %s
            """, placeName, category, reviewSection.toString());

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
                    // [재시도] ECONNRESET 발생 시 최대 2회 즉시 재시도 (안전망)
                    // ECONNRESET은 즉각 실패라 재시도 오버헤드 거의 없음
                    .retryWhen(Retry.max(2)
                            .filter(e -> e.getMessage() != null
                                    && e.getMessage().contains("Connection reset")))
                    .block(Duration.ofSeconds(30)); // 재시도 포함 여유 있게 30초

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
                        "Naver", "데이터 부족으로 분석 불가"
                )
        );
    }
}
