package com.planb.planb_backend.domain.trip.entity;

/**
 * 여행 이동 수단.
 * <p>
 * GapDetectionService, ScoringStrategy, RecommendationService 에서
 * 반경 및 이동 시간 계산에 사용된다.
 * <p>
 * 속도 기준(km/min):
 *   WALK    — 도보  0.08 km/min (약  5 km/h)
 *   TRANSIT — 대중교통 0.35 km/min (약 21 km/h)
 *   CAR     — 차량  0.50 km/min (약 30 km/h)
 */
public enum TransportMode {

    WALK(0.08),
    TRANSIT(0.35),
    CAR(0.50);

    private final double kmPerMin;

    TransportMode(double kmPerMin) {
        this.kmPerMin = kmPerMin;
    }

    public double getKmPerMin() {
        return kmPerMin;
    }
}
