package com.auracademic.backend.controller;

import com.auracademic.backend.dto.*;
import com.auracademic.backend.model.AuditLog;
import com.auracademic.backend.security.UserPrincipal;
import com.auracademic.backend.service.AuditLogService;
import com.auracademic.backend.service.TwoFactorService;
import com.auracademic.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final TwoFactorService twoFactorService;
    private final AuditLogService auditLogService;

    public UserController(UserService userService, TwoFactorService twoFactorService, AuditLogService auditLogService) {
        this.userService = userService;
        this.twoFactorService = twoFactorService;
        this.auditLogService = auditLogService;
    }


    // ─── Profile ──────────────────────────────────────────────────────────────

    /** GET /api/users/me */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userService.getProfile(principal.getId()));
    }

    /** PUT /api/users/me */
    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(principal.getId(), request));
    }

    /** PUT /api/users/me/password */
    @PutMapping("/me/password")
    public ResponseEntity<Map<String, String>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        userService.changePassword(principal.getId(), request, getClientIp(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Mật khẩu đã được thay đổi. Vui lòng đăng nhập lại."));
    }

    // ─── 2FA ──────────────────────────────────────────────────────────────────

    /** GET /api/users/me/2fa/setup */
    @GetMapping("/me/2fa/setup")
    public ResponseEntity<TwoFactorSetupResponse> setup2fa(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userService.setup2fa(principal.getId(), twoFactorService));
    }

    /** POST /api/users/me/2fa/enable */
    @PostMapping("/me/2fa/enable")
    public ResponseEntity<Map<String, String>> enable2fa(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TwoFactorVerifyRequest request,
            HttpServletRequest httpRequest) {
        userService.enable2fa(principal.getId(), request.getCode(), twoFactorService, getClientIp(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Xác thực hai yếu tố đã được bật"));
    }

    /** POST /api/users/me/2fa/disable */
    @PostMapping("/me/2fa/disable")
    public ResponseEntity<Map<String, String>> disable2fa(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TwoFactorVerifyRequest request,
            HttpServletRequest httpRequest) {
        userService.disable2fa(principal.getId(), request.getCode(), twoFactorService, getClientIp(httpRequest));
        return ResponseEntity.ok(Map.of("message", "Xác thực hai yếu tố đã được tắt"));
    }

    // ─── Audit Log ────────────────────────────────────────────────────────────

    /** GET /api/users/me/audit-log */
    @GetMapping("/me/audit-log")
    public ResponseEntity<List<AuditLog>> getAuditLog(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(auditLogService.getUserLogs(principal.getId()));
    }

    // ─── Admin: Get any user profile ─────────────────────────────────────────

    /** GET /api/users/{id} — Admin only */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserProfileResponse> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
