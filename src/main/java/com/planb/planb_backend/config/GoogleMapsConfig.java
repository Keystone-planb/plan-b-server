package com.planb.planb_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yml의 google.maps.api-key 값을 안전하게 읽어오는 설정 클래스
 * 사용법: application-local.yml에 아래와 같이 설정
 *
 * google:
 *   maps:
 *     api-key: "여기에_실제_Google_Maps_API_키_입력"
 */
@Component
@ConfigurationProperties(prefix = "google.maps")
@Getter
@Setter
public class GoogleMapsConfig {

    private String apiKey;
}
