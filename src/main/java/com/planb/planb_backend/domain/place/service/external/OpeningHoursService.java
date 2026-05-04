package com.planb.planb_backend.domain.place.service.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * [기능 6 — 틈새 추천] Place.openingHours JSON(=Google opening_hours.periods)을
 * 파싱해서 "특정 시각에 영업 중인지" 판정한다.
 *
 * <p>Google periods 구조 예시:
 * <pre>
 * {
 *   "weekday_text": [...],
 *   "periods": [
 *     {
 *       "open":  { "day": 1, "time": "0900" },     // 월요일 09:00
 *       "close": { "day": 1, "time": "2200" }      // 월요일 22:00
 *     }
 *   ]
 * }
 * </pre>
 * day: 0=일, 1=월, ..., 6=토 (Google 기준)
 */
@Slf4j
@Service
public class OpeningHoursService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @return openingHoursJson 이 비어있으면 true (정보 부족 시 영업 중 가정).
     *         정상 파싱되면 해당 시각이 어떤 period 에 들어가는지 판정한 boolean.
     */
    public boolean isOpenAt(String openingHoursJson, LocalDateTime when) {
        if (openingHoursJson == null || openingHoursJson.isBlank() || when == null) {
            return true;
        }

        try {
            JsonNode root = objectMapper.readTree(openingHoursJson);
            JsonNode periods = root.get("periods");
            if (periods == null || !periods.isArray() || periods.isEmpty()) {
                return true; // 정보 없음 → false negative 방지
            }

            int googleDay = toGoogleDay(when.getDayOfWeek());
            int nowMinutes = when.getHour() * 60 + when.getMinute();
            int prevDay = (googleDay + 6) % 7;

            for (JsonNode p : periods) {
                JsonNode open = p.get("open");
                JsonNode close = p.get("close");
                if (open == null) continue;

                int openDay = open.path("day").asInt();
                int openMin = parseTime(open.path("time").asText("0000"));

                if (close == null) return true; // 24시간 영업

                int closeDay = close.path("day").asInt();
                int closeMin = parseTime(close.path("time").asText("0000"));

                if (openDay == closeDay) {
                    if (openDay == googleDay && nowMinutes >= openMin && nowMinutes < closeMin) {
                        return true;
                    }
                } else {
                    if (openDay == googleDay && nowMinutes >= openMin) return true;
                    if (closeDay == googleDay && nowMinutes < closeMin && openDay == prevDay) return true;
                }
            }
            return false;

        } catch (Exception e) {
            log.warn("[OpeningHoursService] 파싱 실패 — 영업 중 가정: {}", e.getMessage());
            return true;
        }
    }

    /** Java DayOfWeek (월=1..일=7) → Google day (일=0..토=6) */
    private int toGoogleDay(DayOfWeek dow) {
        return dow.getValue() % 7;
    }

    /** "0930" → 570 (분 단위) */
    private int parseTime(String hhmm) {
        if (hhmm == null || hhmm.length() < 4) return 0;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(2, 4));
        return h * 60 + m;
    }
}
