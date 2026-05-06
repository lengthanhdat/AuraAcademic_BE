package com.auracademic.backend.controller;

import com.auracademic.backend.dto.*;
import com.auracademic.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }


    // ─── Register ─────────────────────────────────────────────────────────────

    /** POST /api/auth/register */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        authService.register(request, getClientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Đăng ký thành công. Vui lòng kiểm tra email để xác thực tài khoản."));
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    /** POST /api/auth/login */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    // ─── Refresh Token ────────────────────────────────────────────────────────

    /** POST /api/auth/refresh */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refreshToken(
                request.getRefreshToken(), 
                getClientIp(httpRequest), 
                httpRequest.getHeader("User-Agent")
        ));
    }

    // ─── Logout ───────────────────────────────────────────────────────────────

    /** POST /api/auth/logout */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            @RequestAttribute(required = false) String currentUserId) {
        authService.logout(request.getRefreshToken(), currentUserId, getClientIp(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Đăng xuất thành công"));
    }

    // ─── Email Verification ───────────────────────────────────────────────────

    /** GET /api/auth/verify-email?token=... */
    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestBody Map<String, String> request) {
        authService.verifyEmail(request.get("email"), request.get("token"));
        return ResponseEntity.ok(Map.of("message", "Xác thực email thành công. Bạn có thể đăng nhập ngay bây giờ."));
    }

    /** POST /api/auth/resend-verification */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.resendVerification(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "Email xác thực đã được gửi lại"));
    }

    // ─── Forgot / Reset Password ──────────────────────────────────────────────

    /** POST /api/auth/forgot-password */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.forgotPassword(request.getEmail(), getClientIp(httpRequest));
        // Luôn trả OK để tránh user enumeration
        return ResponseEntity.ok(Map.of("message", "Nếu email tồn tại, link đặt lại mật khẩu đã được gửi."));
    }

    /** POST /api/auth/reset-password */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        authService.resetPassword(request, getClientIp(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Mật khẩu đã được đặt lại thành công"));
    }

    // ─── Google OAuth2 ────────────────────────────────────────────────────────

    /** POST /api/auth/google */
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(
            @Valid @RequestBody GoogleLoginRequest request,
            HttpServletRequest httpRequest) {
        AuthResponse response = authService.loginWithGoogle(
                request.getIdToken(),
                getClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
