package com.planb.planb_backend.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    /**
     * 인증 코드 발송
     * POST /api/auth/email/request
     * Body: { "email": "..." }
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, String>> requestCode(
            @Valid @RequestBody CodeRequestDto request) {

        emailService.sendCode(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "인증 코드가 발송되었습니다."));
    }

    /**
     * 인증 코드 검증
     * POST /api/auth/email/verify
     * Body: { "email": "...", "code": "..." }
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verifyCode(
            @Valid @RequestBody CodeVerifyDto request) {

        emailService.verifyCode(request.getEmail(), request.getCode());
        return ResponseEntity.ok(Map.of("message", "이메일 인증이 완료되었습니다."));
    }

    // ── 요청 DTO (컨트롤러 전용, 별도 파일 불필요) ──────────────

    @Getter
    static class CodeRequestDto {
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @NotBlank(message = "이메일은 필수입니다.")
        private String email;
    }

    @Getter
    static class CodeVerifyDto {
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @NotBlank(message = "이메일은 필수입니다.")
        private String email;

        @NotBlank(message = "인증 코드는 필수입니다.")
        @Size(min = 6, max = 6, message = "인증 코드는 6자리입니다.")
        private String code;
    }
}
