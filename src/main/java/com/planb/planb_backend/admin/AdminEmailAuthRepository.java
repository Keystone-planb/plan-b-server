package com.planb.planb_backend.admin;

import com.planb.planb_backend.domain.user.entity.EmailAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 어드민 전용 EmailAuth 삭제 쿼리
 * 기존 EmailAuthRepository 에 없는 email 기준 전체 삭제 메서드 추가
 */
public interface AdminEmailAuthRepository extends JpaRepository<EmailAuth, Long> {

    /** 사용자 탈퇴 시: 해당 이메일의 인증 기록 전체 삭제 */
    @Modifying
    @Query("DELETE FROM EmailAuth e WHERE e.email = :email")
    void deleteByEmail(@Param("email") String email);
}
