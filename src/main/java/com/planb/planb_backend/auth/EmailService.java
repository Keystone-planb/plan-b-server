package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.entity.EmailAuth;
import com.planb.planb_backend.domain.user.repository.EmailAuthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

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
     */
    @Transactional
    public void sendCode(String email) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));

        emailAuthRepository.save(EmailAuth.builder()
                .email(email)
                .code(code)
                .expiryDate(LocalDateTime.now().plusMinutes(EXPIRY_MINUTES))
                .build());

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
        mailSender.send(message);
    }

    /**
     * DB에서 최신 코드 조회 → 만료·일치 확인 → 성공 시 verified = true
     */
    @Transactional
    public void verifyCode(String email, String code) {
        EmailAuth emailAuth = emailAuthRepository
                .findTopByEmailOrderByExpiryDateDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("인증 요청 내역이 없습니다."));

        if (emailAuth.isExpired()) {
            throw new IllegalArgumentException("인증 코드가 만료되었습니다. 다시 요청해 주세요.");
        }

        if (!emailAuth.getCode().equals(code)) {
            throw new IllegalArgumentException("인증 코드가 올바르지 않습니다.");
        }

        emailAuth.verify();
    }
}
