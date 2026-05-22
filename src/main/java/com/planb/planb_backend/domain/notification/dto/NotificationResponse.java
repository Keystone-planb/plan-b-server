package com.planb.planb_backend.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class NotificationResponse {

    private Long                    id;
    private Long                    planId;
    private Long                    tripId;        // 여행 ID
    private Integer                 day;           // 몇 번째 날 (Day 1, Day 2 ...)
    private String                  visitTime;     // 방문 시작 시간 "HH:mm"
    private String                  endTime;       // 방문 종료 시간 "HH:mm"
    private String                  type;
    private String                  title;
    private String                  body;
    private Integer                 precipitationProb;
    private LocalDateTime           createdAt;
    private AlternativePlaceDto     originalPlace; // 영향받는 기존 일정 장소
    private List<AlternativePlaceDto> alternatives;
}
