package com.planb.planb_backend.domain.user.repository;

import com.planb.planb_backend.domain.user.entity.EmailAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailAuthRepository extends JpaRepository<EmailAuth, Long> {

    // 이메일 기준 가장 최신 인증 레코드 조회
    Optional<EmailAuth> findTopByEmailOrderByExpiryDateDesc(String email);
}
