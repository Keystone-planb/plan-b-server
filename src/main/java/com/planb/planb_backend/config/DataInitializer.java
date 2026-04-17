package com.planb.planb_backend.config;

import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String testEmail = "dony615@naver.com";

        if (userRepository.existsByEmail(testEmail)) {
            log.info("[DataInitializer] 테스트 계정 이미 존재 → 생성 생략 ({})", testEmail);
            return;
        }

        User testUser = User.builder()
                .email(testEmail)
                .password(passwordEncoder.encode("myPassword123!"))
                .nickname("태형")
                .provider("local")
                .role(Role.USER)
                .build();

        userRepository.save(testUser);
        log.info("[DataInitializer] 테스트 계정 생성 완료 → {}", testEmail);
    }
}
