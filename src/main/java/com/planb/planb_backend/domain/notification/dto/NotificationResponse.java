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
    private String                  type;
    private String                  title;
    private String                  body;
    private Integer                 precipitationProb;
    private LocalDateTime           createdAt;
    private List<AlternativePlaceDto> alternatives;
}
