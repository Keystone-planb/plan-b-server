package com.planb.planb_backend.domain.notification.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Expo Push Notification 발송 서비스.
 * Expo Push API(https://exp.host/--/api/v2/push/send)를 호출하여
 * React Native 앱으로 푸시 알림을 전송한다.
 *
 * [발송 실패 처리]
 * 푸시 발송 실패는 앱 삭제, 토큰 만료 등 외부 원인이 대부분이므로
 * 예외를 로그로만 기록하고 알림 저장 트랜잭션에 영향을 주지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpoPushService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";
    private static final Duration PUSH_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient.Builder webClientBuilder;
    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Expo Push 알림 발송.
     *
     * @param expoPushToken  Expo Push Token ("ExponentPushToken[xxxx]")
     * @param title          알림 제목
     * @param body           알림 본문
     * @param data           클릭 시 앱에 전달할 데이터 (notificationId, tripId, tripPlaceId 등)
     */
    public void sendPush(String expoPushToken, String title, String body, Map<String, Object> data) {
        if (expoPushToken == null || expoPushToken.isBlank()) {
            log.info("[ExpoPush] 토큰 없음 — 발송 스킵");
            return;
        }

        Map<String, Object> payload = Map.of(
                "to",    expoPushToken,
                "title", title,
                "body",  body,
                "data",  data
        );

        try {
            String response = webClient.post()
                    .uri(EXPO_PUSH_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(PUSH_TIMEOUT);

            log.info("[ExpoPush] 발송 완료 — token={}, response={}", expoPushToken, response);
        } catch (Exception e) {
            log.warn("[ExpoPush] 발송 실패 — token={}, error={}", expoPushToken, e.getMessage());
        }
    }
}
