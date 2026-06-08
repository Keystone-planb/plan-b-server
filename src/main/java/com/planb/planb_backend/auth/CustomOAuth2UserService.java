package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
        // Kakao/Google API 호출 타임아웃 설정
        // 기본값 없음 → Kakao 서버 지연 시 ALB idle timeout(60초) 초과 → 502 발생
        // 10초 내 응답 없으면 실패 처리 → failureHandler가 planb://oauth/failure 로 리다이렉트
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.setRestOperations(new RestTemplate(factory));
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId().toUpperCase();
        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        Map<String, Object> attributes = oAuth2User.getAttributes();

        String email;
        String nickname;
        String providerId;

        if ("GOOGLE".equals(registrationId)) {
            email = (String) attributes.get("email");
            nickname = (String) attributes.get("name");
            providerId = (String) attributes.get("sub");
        } else if ("KAKAO".equals(registrationId)) {
            providerId = String.valueOf(attributes.get("id"));
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> properties   = (Map<String, Object>) attributes.get("properties");
            String rawEmail = kakaoAccount != null ? (String) kakaoAccount.get("email") : null;
            email    = (rawEmail != null && !rawEmail.isBlank()) ? rawEmail : providerId + "@kakao.com";
            nickname = properties != null ? (String) properties.get("nickname") : "카카오유저";
        } else {
            throw new OAuth2AuthenticationException("지원하지 않는 소셜 로그인입니다: " + registrationId);
        }

        final String finalEmail      = email;
        final String finalNickname   = nickname;
        final String finalProviderId = providerId;
        final String providerLower   = registrationId.toLowerCase();

        User user;
        try {
            user = resolveUser(providerLower, finalProviderId, finalEmail, finalNickname);
        } catch (DataIntegrityViolationException e) {
            // 극히 드문 Race Condition(동시 첫 로그인) 방어
            log.warn("[OAuth2] 중복 저장 감지 (Race Condition) — provider={}, providerId={}", providerLower, finalProviderId);
            user = userRepository.findByProviderAndProviderId(providerLower, finalProviderId)
                    .or(() -> userRepository.findByEmail(finalEmail))
                    .orElseThrow(() -> new OAuth2AuthenticationException("소셜 로그인 처리 중 오류가 발생했습니다."));
        }

        Map<String, Object> modifiedAttributes = new java.util.HashMap<>(attributes);
        modifiedAttributes.put("userId", user.getId());

        return new DefaultOAuth2User(List.of(), modifiedAttributes, userNameAttributeName);
    }

    /**
     * [Upsert 핵심 로직]
     *
     * 1순위: provider + providerId 로 조회 → 기존 소셜 회원 (재로그인)
     * 2순위: email 로 조회             → 동일 이메일로 다른 방식(이메일/타 소셜)으로 가입한 기존 회원
     * 3순위: 둘 다 없으면             → 진짜 신규 회원으로 INSERT
     *
     * 2순위에서 기존 회원을 찾으면 INSERT 없이 그대로 반환
     * (provider 필드는 최초 가입 방식을 보존하기 위해 덮어쓰지 않음)
     */
    private User resolveUser(String provider, String providerId,
                             String email, String nickname) {

        // 1순위: 동일 소셜 계정으로 이미 가입된 경우 (정상 재로그인)
        Optional<User> byProvider = userRepository.findByProviderAndProviderId(provider, providerId);
        if (byProvider.isPresent()) {
            log.debug("[OAuth2] 기존 소셜 회원 확인 — provider={}, providerId={}", provider, providerId);
            return byProvider.get();
        }

        // 2순위: 같은 이메일로 다른 방법(이메일 회원가입 또는 타 소셜)으로 가입된 경우
        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            log.info("[OAuth2] 이메일 기존 회원 — email={}, 기존 provider={}, 소셜 로그인 provider={}",
                    email, byEmail.get().getProvider(), provider);
            // INSERT 없이 기존 회원 그대로 반환 (이메일 Unique 충돌 원천 차단)
            return byEmail.get();
        }

        // 3순위: 완전 신규 회원
        log.info("[OAuth2] 신규 소셜 회원 가입 — provider={}, email={}", provider, email);
        return userRepository.save(User.builder()
                .email(email)
                .nickname(nickname)
                .provider(provider)
                .providerId(providerId)
                .role(Role.USER)
                .build());
    }
}
