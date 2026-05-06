package com.planb.planb_backend.domain.place.service.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [기능 6 — 틈새 추천] OpeningHoursService 단위 테스트
 *
 * Google opening_hours.periods JSON 파싱 및 영업 여부 판정 검증
 */
class OpeningHoursServiceTest {

    private OpeningHoursService service;

    // 월~금 09:00~22:00 / 토~일 10:00~20:00 영업 JSON
    private static final String WEEKDAY_JSON = """
        {
          "periods": [
            { "open": {"day": 1, "time": "0900"}, "close": {"day": 1, "time": "2200"} },
            { "open": {"day": 2, "time": "0900"}, "close": {"day": 2, "time": "2200"} },
            { "open": {"day": 3, "time": "0900"}, "close": {"day": 3, "time": "2200"} },
            { "open": {"day": 4, "time": "0900"}, "close": {"day": 4, "time": "2200"} },
            { "open": {"day": 5, "time": "0900"}, "close": {"day": 5, "time": "2200"} },
            { "open": {"day": 6, "time": "1000"}, "close": {"day": 6, "time": "2000"} },
            { "open": {"day": 0, "time": "1000"}, "close": {"day": 0, "time": "2000"} }
          ]
        }""";

    // 자정 넘기는 영업: 금요일 22:00 ~ 토요일 03:00
    private static final String CROSS_MIDNIGHT_JSON = """
        {
          "periods": [
            { "open": {"day": 5, "time": "2200"}, "close": {"day": 6, "time": "0300"} }
          ]
        }""";

    // 24시간 영업 (close 없음)
    private static final String ALWAYS_OPEN_JSON = """
        {
          "periods": [
            { "open": {"day": 0, "time": "0000"} }
          ]
        }""";

    @BeforeEach
    void setUp() {
        service = new OpeningHoursService();
    }

    @Test
    @DisplayName("null JSON → 영업 중 가정(true)")
    void nullJson_returnsTrue() {
        assertThat(service.isOpenAt(null, LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("빈 JSON → 영업 중 가정(true)")
    void blankJson_returnsTrue() {
        assertThat(service.isOpenAt("", LocalDateTime.now())).isTrue();
        assertThat(service.isOpenAt("  ", LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("when=null → true")
    void nullWhen_returnsTrue() {
        assertThat(service.isOpenAt(WEEKDAY_JSON, null)).isTrue();
    }

    @Test
    @DisplayName("월요일 10:00 → 영업 중")
    void monday_10am_isOpen() {
        // 2026-05-04 는 월요일
        LocalDateTime when = LocalDateTime.of(2026, 5, 4, 10, 0);
        assertThat(service.isOpenAt(WEEKDAY_JSON, when)).isTrue();
    }

    @Test
    @DisplayName("월요일 08:59 → 영업 전")
    void monday_before_open_isClosed() {
        LocalDateTime when = LocalDateTime.of(2026, 5, 4, 8, 59);
        assertThat(service.isOpenAt(WEEKDAY_JSON, when)).isFalse();
    }

    @Test
    @DisplayName("월요일 22:00 → 영업 종료 시각 (닫힘)")
    void monday_exactly_closing_isClosed() {
        LocalDateTime when = LocalDateTime.of(2026, 5, 4, 22, 0);
        assertThat(service.isOpenAt(WEEKDAY_JSON, when)).isFalse();
    }

    @Test
    @DisplayName("토요일 12:00 → 영업 중 (주말 단축)")
    void saturday_noon_isOpen() {
        // 2026-05-02 는 토요일
        LocalDateTime when = LocalDateTime.of(2026, 5, 2, 12, 0);
        assertThat(service.isOpenAt(WEEKDAY_JSON, when)).isTrue();
    }

    @Test
    @DisplayName("토요일 20:30 → 주말 영업 종료 후 (닫힘)")
    void saturday_after_close_isClosed() {
        LocalDateTime when = LocalDateTime.of(2026, 5, 2, 20, 30);
        assertThat(service.isOpenAt(WEEKDAY_JSON, when)).isFalse();
    }

    @Test
    @DisplayName("자정 넘기는 영업 — 금요일 23:00 → 영업 중")
    void crossMidnight_friday_23_isOpen() {
        // 2026-05-01 는 금요일
        LocalDateTime when = LocalDateTime.of(2026, 5, 1, 23, 0);
        assertThat(service.isOpenAt(CROSS_MIDNIGHT_JSON, when)).isTrue();
    }

    @Test
    @DisplayName("자정 넘기는 영업 — 토요일 02:00 → 영업 중 (전날 연장)")
    void crossMidnight_saturday_02_isOpen() {
        // 2026-05-02 는 토요일
        LocalDateTime when = LocalDateTime.of(2026, 5, 2, 2, 0);
        assertThat(service.isOpenAt(CROSS_MIDNIGHT_JSON, when)).isTrue();
    }

    @Test
    @DisplayName("자정 넘기는 영업 — 토요일 03:00 → 닫힘")
    void crossMidnight_saturday_03_isClosed() {
        LocalDateTime when = LocalDateTime.of(2026, 5, 2, 3, 0);
        assertThat(service.isOpenAt(CROSS_MIDNIGHT_JSON, when)).isFalse();
    }

    @Test
    @DisplayName("24시간 영업 (close 없음) → 항상 영업 중")
    void alwaysOpen_isAlwaysTrue() {
        // 일요일, 월요일, 어느 시간이든
        assertThat(service.isOpenAt(ALWAYS_OPEN_JSON, LocalDateTime.of(2026, 5, 3, 3, 0))).isTrue();
        assertThat(service.isOpenAt(ALWAYS_OPEN_JSON, LocalDateTime.of(2026, 5, 4, 15, 30))).isTrue();
    }

    @Test
    @DisplayName("periods 비어있는 JSON → 정보 부족이므로 true(영업 중 가정)")
    void emptyPeriods_returnsTrue() {
        String json = "{\"periods\": []}";
        assertThat(service.isOpenAt(json, LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("잘못된 JSON → 파싱 실패 시 true(영업 중 가정)")
    void malformedJson_returnsTrue() {
        assertThat(service.isOpenAt("{not valid json", LocalDateTime.now())).isTrue();
    }
}
