package com.planb.planb_backend.domain.user.controller;

import com.planb.planb_backend.auth.AuthService;
import com.planb.planb_backend.domain.user.dto.AuthResponse;
import com.planb.planb_backend.domain.user.dto.SignupRequest;
import com.planb.planb_backend.domain.user.dto.UpdatePreferenceRequest;
import com.planb.planb_backend.domain.user.dto.UserProfileResponse;
import com.planb.planb_backend.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "사용자", description = "유저 프로필 및 마이페이지 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;

    @Operation(summary = "회원가입", description = "이메일 인증이 완료된 계정으로 회원가입합니다.")
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
        summary = "내 프로필 조회",
        description = "현재 로그인한 유저의 프로필 정보(이메일, 닉네임, 가입 경로, 여행 취향)를 반환합니다."
    )
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMe(Authentication authentication) {
        UserProfileResponse profile = userService.getProfile(authentication.getName());
        return ResponseEntity.ok(profile);
    }

    @Operation(
        summary = "취향 정보 수정",
        description = "유저의 여행 스타일(travelStyle)과 선호 장소 유형(preferences) 목록을 업데이트합니다. " +
                      "travelStyle 예시: HEALING, ACTIVE, TRENDY, CLASSIC, LOCAL. " +
                      "preferences 예시: [\"FOOD\", \"CAFE\", \"PARK\"]"
    )
    @PatchMapping("/me/preferences")
    public ResponseEntity<Map<String, String>> updatePreferences(
            Authentication authentication,
            @RequestBody UpdatePreferenceRequest request) {
        userService.updatePreferences(authentication.getName(), request);
        return ResponseEntity.ok(Map.of("message", "취향 정보가 업데이트되었습니다."));
    }

    @Operation(summary = "회원 탈퇴", description = "현재 로그인한 유저의 계정을 탈퇴 처리합니다.")
    @DeleteMapping("/me")
    public ResponseEntity<Map<String, String>> withdraw(Authentication authentication) {
        authService.withdraw(authentication.getName());
        return ResponseEntity.ok(Map.of("message", "회원 탈퇴가 완료되었습니다."));
    }
}
