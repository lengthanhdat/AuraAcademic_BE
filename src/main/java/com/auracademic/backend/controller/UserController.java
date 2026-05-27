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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final TwoFactorService twoFactorService;
    private final AuditLogService auditLogService;
    private final com.auracademic.backend.repository.UserRepository userRepository;

    public UserController(UserService userService, TwoFactorService twoFactorService, AuditLogService auditLogService, com.auracademic.backend.repository.UserRepository userRepository) {
        this.userService = userService;
        this.twoFactorService = twoFactorService;
        this.auditLogService = auditLogService;
        this.userRepository = userRepository;
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

    /** PUT /api/users/me/avatar */
    @PutMapping("/me/avatar")
    public ResponseEntity<UserProfileResponse> updateAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> request) {
        String base64Avatar = request.get("avatarUrl");
        if (base64Avatar == null || base64Avatar.isBlank()) {
            throw new IllegalArgumentException("Ảnh đại diện không được trống");
        }
        if (!base64Avatar.startsWith("data:image/")) {
            throw new IllegalArgumentException("Định dạng ảnh không hợp lệ (chỉ nhận JPG, PNG, WEBP)");
        }
        return ResponseEntity.ok(userService.updateAvatar(principal.getId(), base64Avatar));
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

    // ─── Favorites ────────────────────────────────────────────────────────────

    @PostMapping("/me/favorite-practice/{examId}")
    public ResponseEntity<?> toggleFavoritePractice(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String examId) {
        return userRepository.findById(principal.getId()).map(user -> {
            List<String> favs = user.getFavoritePracticeIds();
            if (favs == null) {
                favs = new java.util.ArrayList<>();
            }
            boolean isFavorite = false;
            if (favs.contains(examId)) {
                favs.remove(examId);
            } else {
                favs.add(examId);
                isFavorite = true;
            }
            user.setFavoritePracticeIds(favs);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "Đã cập nhật yêu thích", "isFavorite", isFavorite));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─── Admin: Get any user profile ─────────────────────────────────────────

    /** GET /api/users/{id} — Admin only */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserProfileResponse> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    // ─── Teacher Verification ─────────────────────────────────────────────────

    /** POST /api/users/verification-request — Teacher submits verification proof */
    @PostMapping("/verification-request")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> submitVerificationRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody TeacherVerificationRequest request) {
        return userRepository.findById(principal.getId()).map(user -> {
            String currentStatus = user.getVerificationStatus();
            // Only STANDARD or REJECTED teachers can submit
            if ("PENDING".equals(currentStatus) || "VERIFIED".equals(currentStatus)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "PENDING".equals(currentStatus)
                        ? "Yêu cầu của bạn đang được xem xét."
                        : "Tài khoản của bạn đã được xác thực."
                ));
            }
            if (request.getProofUrl() == null || request.getProofUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng cung cấp liên kết hoặc ảnh chứng minh."));
            }
            user.setVerificationStatus("PENDING");
            user.setVerificationProofUrl(request.getProofUrl());
            user.setVerificationProofType(request.getProofType());
            user.setVerificationNote(null);
            user.setVerificationRequestedAt(java.time.LocalDateTime.now());
            userRepository.save(user);

            TeacherVerificationResponse response = new TeacherVerificationResponse(
                "PENDING",
                user.getVerificationRequestedAt(),
                null,
                null,
                "Yêu cầu xác thực đã được gửi. Chúng tôi sẽ phản hồi trong vòng 24 giờ."
            );
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/users/me/verification — Get current verification status */
    @GetMapping("/me/verification")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<?> getVerificationStatus(
            @AuthenticationPrincipal UserPrincipal principal) {
        return userRepository.findById(principal.getId()).map(user -> {
            TeacherVerificationResponse response = new TeacherVerificationResponse(
                user.getVerificationStatus() != null ? user.getVerificationStatus() : "STANDARD",
                user.getVerificationRequestedAt(),
                user.getVerifiedAt(),
                user.getVerificationNote(),
                null
            );
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
