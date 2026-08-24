package com.planb.planb_backend.domain.place.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * [관측성] OpenAI 호출 1건에 대한 기록.
 * OpenAiAnalysisService의 모든 호출(원 요청 + self-repair 재질의)이 끝날 때마다 1행씩 남는다.
 * /api/admin/ai-metrics 에서 기간별로 집계해 지연시간/재시도/재질의/폴백/토큰/예상비용을 보여준다.
 */
@Entity
@Table(name = "ai_call_logs")
@Getter
@Setter
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String placeName;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long latencyMs;

    private Integer retryCount;

    private boolean repairAttempted;

    private boolean repairSucceeded;

    private boolean fallbackTriggered;

    private LocalDateTime createdAt;
}
