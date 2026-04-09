package com.planb.planb_backend.domain.user.controller;

import com.planb.planb_backend.auth.AuthService;
import com.planb.planb_backend.domain.user.dto.AuthResponse;
import com.planb.planb_backend.domain.user.dto.SignupRequest;
import com.planb.planb_backend.domain.user.dto.UserResponse;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserRepository userRepository;

    /**
     * 일반 회원가입
     * POST /api/users/signup
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내 정보 조회
     * GET /api/users/me
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMe(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return ResponseEntity.ok(UserResponse.from(user));
    }

    /**
     * 회원 탈퇴
     * DELETE /api/users/me
     */
    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> withdraw(Authentication authentication) {
        String email = authentication.getName();
        authService.withdraw(email);
        return ResponseEntity.ok(Map.of("message", "회원 탈퇴가 완료되었습니다."));
    }
}
