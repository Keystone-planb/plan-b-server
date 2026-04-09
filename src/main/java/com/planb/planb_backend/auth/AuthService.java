package com.planb.planb_backend.auth;

import com.planb.planb_backend.domain.user.dto.AuthResponse;
import com.planb.planb_backend.domain.user.dto.LoginRequest;
import com.planb.planb_backend.domain.user.dto.SignupRequest;
import com.planb.planb_backend.domain.user.entity.EmailAuth;
import com.planb.planb_backend.domain.user.entity.RefreshToken;
import com.planb.planb_backend.domain.user.entity.Role;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.EmailAuthRepository;
import com.planb.planb_backend.domain.user.repository.RefreshTokenRepository;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import com.planb.planb_backend.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailAuthRepository emailAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    // Refresh Token 유효 기간: 14일
    private static final long REFRESH_TOKEN_DAYS = 14;

    /**
     * 일반 회원가입
     * - 이메일 중복 체크 후 BCrypt 암호화 저장
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        // 이메일 인증 완료 여부 확인 (방어 로직 최상단)
        EmailAuth emailAuth = emailAuthRepository
                .findTopByEmailOrderByExpiryDateDesc(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 인증이 필요합니다."));

        if (!emailAuth.isVerified()) {
            throw new IllegalArgumentException("이메일 인증이 완료되지 않았습니다.");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .provider("local")
                .role(Role.USER)
                .build();

        User saved = userRepository.save(user);
        return AuthResponse.ofSignup(saved.getId());
    }

    /**
     * 일반 로그인
     * - 이메일/비밀번호 검증 후 Access + Refresh Token 발급
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return issueTokens(user);
    }

    /**
     * Refresh Token으로 새 Access Token 발급
     * - DB 토큰과 일치 여부 확인 + 만료 검사
     */
    @Transactional
    public AuthResponse refreshToken(String refreshTokenValue) {
        RefreshToken saved = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 Refresh Token입니다."));

        if (saved.isExpired()) {
            refreshTokenRepository.delete(saved);
            throw new IllegalArgumentException("만료된 Refresh Token입니다. 다시 로그인해 주세요.");
        }

        User user = saved.getUser();
        String newAccessToken = jwtProvider.createAccessToken(user.getEmail(), user.getRole().name());

        return AuthResponse.ofAccessToken(newAccessToken, user.getId(), user.getNickname());
    }

    /**
     * 로그아웃
     * - DB에서 Refresh Token 삭제
     */
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(refreshTokenRepository::delete);
    }

    /**
     * 회원 탈퇴
     * - status를 WITHDRAWN으로 변경 + Refresh Token 삭제
     */
    @Transactional
    public void withdraw(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.withdraw();
        refreshTokenRepository.findByUser(user)
                .ifPresent(refreshTokenRepository::delete);
    }

    /**
     * Access + Refresh Token 동시 발급 (로그인 & 소셜 로그인 공용)
     * - 기존 Refresh Token이 있으면 덮어쓰기
     */
    @Transactional
    public AuthResponse issueTokens(User user) {
        String accessToken = jwtProvider.createAccessToken(user.getEmail(), user.getRole().name());
        String refreshTokenValue = jwtProvider.createRefreshToken(user.getId());

        // 기존 토큰 삭제 → flush로 DB 반영 → 새 토큰 저장
        refreshTokenRepository.deleteByUser(user);
        refreshTokenRepository.flush();

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(refreshTokenValue)
                .expiryDate(LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS))
                .build());

        return AuthResponse.ofLogin(accessToken, refreshTokenValue, user.getId(), user.getNickname());
    }
}
