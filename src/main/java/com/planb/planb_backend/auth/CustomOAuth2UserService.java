package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
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
            // 카카오 응답 구조
            providerId = String.valueOf(attributes.get("id"));
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> properties = (Map<String, Object>) attributes.get("properties");
            String rawEmail = kakaoAccount != null ? (String) kakaoAccount.get("email") : null;
            email = (rawEmail != null && !rawEmail.isBlank()) ? rawEmail : providerId + "@kakao.com";
            nickname = properties != null ? (String) properties.get("nickname") : "카카오유저";
        } else {
            throw new OAuth2AuthenticationException("지원하지 않는 소셜 로그인입니다: " + registrationId);
        }

        // 최초 로그인이면 자동 회원가입
        final String finalEmail = email;
        final String finalNickname = nickname;
        final String finalProviderId = providerId;

        User user;
        try {
            user = userRepository.findByProviderAndProviderId(registrationId, providerId)
                    .orElseGet(() -> userRepository.save(User.builder()
                            .email(finalEmail)
                            .nickname(finalNickname)
                            .provider(registrationId.toLowerCase())
                            .providerId(finalProviderId)
                            .role(Role.USER)
                            .build()));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 중복 저장 시도 시 → 이미 저장된 유저를 다시 조회
            log.warn("[OAuth2] 중복 저장 감지 (Race Condition) — provider={}, providerId={}", registrationId, providerId);
            user = userRepository.findByProviderAndProviderId(registrationId, providerId)
                    .orElseThrow(() -> new OAuth2AuthenticationException("소셜 로그인 처리 중 오류가 발생했습니다."));
        }

        // userId를 attributes에 담아서 SuccessHandler로 전달
        Map<String, Object> modifiedAttributes = new java.util.HashMap<>(attributes);
        modifiedAttributes.put("userId", user.getId());

        return new DefaultOAuth2User(List.of(), modifiedAttributes, userNameAttributeName);
    }
}
