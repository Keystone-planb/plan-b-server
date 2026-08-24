package com.planb.planb_backend.domain.place.service.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planb.planb_backend.domain.trip.entity.Mood;
import com.planb.planb_backend.domain.trip.entity.PlaceType;
import com.planb.planb_backend.domain.trip.entity.Space;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenAiAnalysisService {

    private static final String MODEL = "gpt-4o-mini";

    private static final String SYSTEM_PROMPT =
            "You are a factual travel data analyst. Never invent information not present in the source data.";

    private final String apiKey;
    private final AiCallLogService aiCallLogService;
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

    private final WebClient webClient;

    public OpenAiAnalysisService(
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            AiCallLogService aiCallLogService) {
        this.apiKey = apiKey;
        this.aiCallLogService = aiCallLogService;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(OPENAI_POOL)
                                .responseTimeout(Duration.ofSeconds(20))))
                .build();
    }

    /**
     * GPT-4o-mini로 장소 리뷰 분석 요청
     * 반환: space, type, mood, review_data, summaries(플랫폼별), reasoning(내부 사고용 — DB 저장 안 함)
     *
     * [하네스 흐름]
     * 1) 1차 호출 → JSON 파싱 → 스키마/enum 검증
     * 2) 검증 실패 시 "왜 틀렸는지"를 알려주는 재질의(self-repair) 1회 시도
     * 3) 그래도 실패하면 안전한 기본값(fallback)으로 대체
     * 매 호출의 지연시간/재시도횟수/재질의여부/토큰 사용량은 AiCallLogService에 기록되어
     * /api/admin/ai-metrics 로 조회 가능
     */
    public Map<String, Object> requestAnalysis(String placeName, String category, Map<String, List<String>> reviews) {
        long startedAt = System.currentTimeMillis();
        AtomicInteger retryCounter = new AtomicInteger(0);
        String prompt = buildPrompt(placeName, category, buildReviewSection(reviews));

        boolean repairAttempted = false;
        boolean repairSucceeded = false;
        Integer promptTokens = null;
        Integer completionTokens = null;

        try {
            Map<String, Object> response = callOpenAi(List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)
            ), retryCounter);

            Integer[] usage = extractUsage(response);
            promptTokens = usage[0];
            completionTokens = usage[1];

            String content = extractContent(response);
            if (content == null) {
                throw new IllegalStateException("OpenAI 응답에 choices가 없습니다.");
            }

            Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
            List<String> errors = validate(parsed);

            if (!errors.isEmpty()) {
                repairAttempted = true;
                log.warn("AI 응답 검증 실패 (Place: {}) — 재질의 시도: {}", placeName, errors);
                Map<String, Object> repaired = attemptRepair(prompt, content, errors, retryCounter);

                if (repaired == null) {
                    logCall(placeName, promptTokens, completionTokens, startedAt, retryCounter.get(),
                            true, false, true);
                    return fallbackResponse();
                }
                parsed = repaired;
                repairSucceeded = true;
            }

            logCall(placeName, promptTokens, completionTokens, startedAt, retryCounter.get(),
                    repairAttempted, repairSucceeded, false);
            return parsed;

        } catch (Exception e) {
            log.error("AI 분석 실패 (Place: {}): {}", placeName, e.getMessage());
            logCall(placeName, promptTokens, completionTokens, startedAt, retryCounter.get(),
                    repairAttempted, repairSucceeded, true);
            return fallbackResponse();
        }
    }

    /**
     * [출력 검증] 응답이 우리가 정의한 스키마(enum 값 등)를 지켰는지 확인.
     * package-private: 단위 테스트(OpenAiAnalysisServiceValidationTest)에서 네트워크 호출 없이 직접 검증
     */
    List<String> validate(Map<String, Object> parsed) {
        List<String> errors = new ArrayList<>();
        if (parsed == null) {
            errors.add("응답이 비어 있습니다.");
            return errors;
        }

        errors.addAll(validateEnumField(parsed, "space", Space.class));
        errors.addAll(validateEnumField(parsed, "type", PlaceType.class));
        errors.addAll(validateEnumField(parsed, "mood", Mood.class));

        Object reviewData = parsed.get("review_data");
        if (!(reviewData instanceof String) || ((String) reviewData).isBlank()) {
            errors.add("review_data 필드가 없거나 비어 있습니다.");
        }

        Object summaries = parsed.get("summaries");
        if (!(summaries instanceof Map) || ((Map<?, ?>) summaries).isEmpty()) {
            errors.add("summaries 필드가 없거나 객체가 아닙니다.");
        }

        return errors;
    }

    private <E extends Enum<E>> List<String> validateEnumField(Map<String, Object> parsed, String field, Class<E> enumType) {
        Object value = parsed.get(field);
        if (value == null) {
            return List.of(field + " 필드가 없습니다.");
        }
        try {
            Enum.valueOf(enumType, value.toString().trim().toUpperCase());
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(field + " 값이 유효하지 않습니다: '" + value
                    + "' (허용값: " + Arrays.toString(enumType.getEnumConstants()) + ")");
        }
    }

    /**
     * [Self-repair] 검증 실패 이유를 모델에게 그대로 알려주고, 같은 대화 맥락(원 프롬프트 + 잘못된 응답)에서
     * 스키마를 지켜 다시 답하게 한다. 1회만 시도 — 재귀적으로 반복하면 무한 재질의/비용 폭주 위험
     */
    private Map<String, Object> attemptRepair(String originalPrompt, String invalidContent,
                                               List<String> errors, AtomicInteger retryCounter) {
        String repairInstruction = "방금 응답이 아래 이유로 형식에 맞지 않았다:\n"
                + errors.stream().map(e -> "- " + e).collect(Collectors.joining("\n"))
                + "\n\n같은 입력 데이터를 기준으로, 지정된 JSON 스키마와 enum 값만 사용해서 다시 응답하라.";

        try {
            Map<String, Object> response = callOpenAi(List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", originalPrompt),
                    Map.of("role", "assistant", "content", invalidContent),
                    Map.of("role", "user", "content", repairInstruction)
            ), retryCounter);

            String content = extractContent(response);
            if (content == null) return null;

            Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
            return validate(parsed).isEmpty() ? parsed : null;

        } catch (Exception e) {
            log.warn("AI 응답 재질의(self-repair) 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * OpenAI Chat Completions 호출 (원 요청/재질의 공용)
     * [재시도] ECONNRESET(연결 끊김) + 429(Rate Limit 초과) 모두 대응
     * backoff: 첫 재시도 1초 → 2초 → 4초 간격 (합계 7초)
     * SSE orTimeout(30s) 내에서 재시도 + 응답(20초) 여유분 확보
     */
    private Map<String, Object> callOpenAi(List<Map<String, Object>> messages, AtomicInteger retryCounter) {
        return webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(Map.of(
                        "model", MODEL,
                        "messages", messages,
                        "response_format", Map.of("type", "json_object")
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(e -> e.getMessage() != null && (
                                e.getMessage().contains("Connection reset") ||
                                e.getMessage().contains("429")))
                        .doBeforeRetry(signal -> retryCounter.incrementAndGet()))
                .block(Duration.ofSeconds(20)); // OpenAI 단일 응답 타임아웃 (재시도 7초 + 응답 20초 = 27초 < orTimeout 30초)
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null || !response.containsKey("choices")) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        return (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
    }

    @SuppressWarnings("unchecked")
    private Integer[] extractUsage(Map<String, Object> response) {
        if (response == null || !response.containsKey("usage")) return new Integer[]{null, null};
        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        Integer prompt = usage.get("prompt_tokens") != null ? ((Number) usage.get("prompt_tokens")).intValue() : null;
        Integer completion = usage.get("completion_tokens") != null ? ((Number) usage.get("completion_tokens")).intValue() : null;
        return new Integer[]{prompt, completion};
    }

    private void logCall(String placeName, Integer promptTokens, Integer completionTokens, long startedAt,
                          int retryCount, boolean repairAttempted, boolean repairSucceeded, boolean fallbackTriggered) {
        long latencyMs = System.currentTimeMillis() - startedAt;
        aiCallLogService.record(placeName, MODEL, promptTokens, completionTokens, latencyMs,
                retryCount, repairAttempted, repairSucceeded, fallbackTriggered);
    }

    private Map<String, Object> fallbackResponse() {
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

    private String buildReviewSection(Map<String, List<String>> reviews) {
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
        return reviewSection.toString();
    }

    private String buildPrompt(String placeName, String category, String reviewSection) {
        return String.format("""
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
            """, placeName, category, reviewSection);
    }
}
