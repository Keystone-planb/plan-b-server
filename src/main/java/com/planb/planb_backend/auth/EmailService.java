package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.entity.EmailAuth;
import com.planb.planb_backend.domain.user.repository.EmailAuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final EmailAuthRepository emailAuthRepository;

    @Value("${spring.mail.username}")
    private String senderEmail;

    private static final int EXPIRY_MINUTES = 3;

    /**
     * 6자리 난수 생성 → 메일 발송 → DB 저장 (만료 3분)
     * 메일 발송 성공 후 DB 저장 (발송 실패 시 DB 저장 방지)
     */
    @Transactional
    public void sendCode(String email) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(senderEmail);
        message.setTo(email);
        message.setSubject("[Plan B] 이메일 인증 코드");
        message.setText(
                "Plan B 이메일 인증 코드입니다.\n\n" +
                "인증 코드: " + code + "\n\n" +
                "3분 이내에 입력해 주세요.\n" +
                "본인이 요청하지 않았다면 이 메일을 무시하세요."
        );

        log.info("[EmailService] 인증 코드 발송 시도 → 수신자: {}", email);
        try {
            mailSender.send(message);
            log.info("[EmailService] 인증 코드 발송 성공 → 수신자: {}", email);
        } catch (MailException e) {
            log.error("[EmailService] 인증 코드 발송 실패 → 수신자: {}, 원인: {}", email, e.getMessage(), e);
            throw new RuntimeException("이메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }

        emailAuthRepository.save(EmailAuth.builder()
                .email(email)
                .code(code)
                .expiryDate(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .build());
    }

    /**
     * DB에서 최신 코드 조회 → 만료·일치 확인 → 성공 시 verified = true
     */
    @Transactional
    public void verifyCode(String email, String code) {
        EmailAuth emailAuth = emailAuthRepository
                .findTopByEmailOrderByExpiryDateDesc(email)
                .orElseThrow(() -> {
                    log.warn("[EmailService] 인증 요청 내역 없음 → 이메일: {}", email);
                    return new IllegalArgumentException("인증 요청 내역이 없습니다.");
                });

        if (emailAuth.isExpired()) {
            log.warn("[EmailService] 인증 코드 만료 → 이메일: {}", email);
            throw new IllegalArgumentException("인증 코드가 만료되었습니다. 다시 요청해 주세요.");
        }

        if (!emailAuth.getCode().equals(code)) {
            log.warn("[EmailService] 인증 코드 불일치 → 이메일: {}", email);
            throw new IllegalArgumentException("인증 코드가 올바르지 않습니다.");
        }

        emailAuth.verify();
        log.info("[EmailService] 이메일 인증 완료 → 이메일: {}", email);
    }
}
