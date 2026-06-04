package com.auracademic.backend.controller;

import com.auracademic.backend.model.AuditLog;
import com.auracademic.backend.model.Notification;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.AuditLogRepository;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.repository.NotificationRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.util.ClientInfoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ExamResultRepository resultRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}")
    private String smtpUsername;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.host:}")
    private String smtpHost;

    @org.springframework.beans.factory.annotation.Value("${app.google.client-id:}")
    private String googleClientId;

    // ─── Stats ───────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            long totalUsers    = userRepository.count();
            long totalTeachers = userRepository.countByRole("teacher");
            long totalStudents = userRepository.countByRole("student");
            long totalAdmins   = userRepository.countByRole("admin");
            long totalExams    = examRepository.count();
            long publishedExams = examRepository.countByStatus("PUBLISHED");
            long totalResults  = resultRepository.count();
            long verifiedUsers = userRepository.findAll().stream().filter(User::isEmailVerified).count();
            long lockedUsers   = userRepository.findAll().stream().filter(User::isAccountLocked).count();
            long pendingVerifications = userRepository.findAll().stream()
                .filter(u -> "teacher".equalsIgnoreCase(u.getRole()) && "PENDING".equals(u.getVerificationStatus()))
                .count();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalUsers", totalUsers);
            stats.put("totalTeachers", totalTeachers);
            stats.put("totalStudents", totalStudents);
            stats.put("totalAdmins", totalAdmins);
            stats.put("totalExams", totalExams);
            stats.put("publishedExams", publishedExams);
            stats.put("totalResults", totalResults);
            stats.put("verifiedUsers", verifiedUsers);
            stats.put("lockedUsers", lockedUsers);
            stats.put("pendingVerifications", pendingVerifications);

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Users CRUD ───────────────────────────────────────────────────────────

    /** GET /api/admin/users */
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search) {
        try {
            List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
            if (role != null && !role.isBlank()) {
                users = users.stream().filter(u -> role.equals(u.getRole())).collect(Collectors.toList());
            }
            if (search != null && !search.isBlank()) {
                String q = search.toLowerCase();
                users = users.stream()
                        .filter(u -> (u.getFullName() != null && u.getFullName().toLowerCase().contains(q))
                                  || (u.getEmail() != null && u.getEmail().toLowerCase().contains(q)))
                        .collect(Collectors.toList());
            }
            users.forEach(u -> u.setPassword("[HIDDEN]"));
            return ResponseEntity.ok(users);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/admin/users/{id} */
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable String id) {
        return userRepository.findById(id)
                .map(u -> { u.setPassword("[HIDDEN]"); return ResponseEntity.ok(u); })
                .orElse(ResponseEntity.notFound().build());
    }

    /** POST /api/admin/users */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("email");
            if (email == null || userRepository.existsByEmail(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Email không hợp lệ hoặc đã tồn tại"));
            }
            User user = new User();
            user.setFullName(body.get("fullName"));
            user.setEmail(email);
            user.setPassword("[TEMP]");
            user.setRole(body.getOrDefault("role", "student"));
            user.setProvider("local");
            user.setEmailVerified(true);
            user.setAccountLocked(false);
            user.setFailedLoginAttempts(0);
            user.setCreatedAt(LocalDateTime.now());
            User saved = userRepository.save(user);
            saved.setPassword("[HIDDEN]");
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUT /api/admin/users/{id} */
    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return userRepository.findById(id).map(user -> {
            if (body.containsKey("fullName")) user.setFullName((String) body.get("fullName"));
            if (body.containsKey("role")) {
                String r = (String) body.get("role");
                if (List.of("student","teacher","admin").contains(r)) user.setRole(r);
            }
            if (body.containsKey("accountLocked")) user.setAccountLocked((Boolean) body.get("accountLocked"));
            if (body.containsKey("emailVerified")) user.setEmailVerified((Boolean) body.get("emailVerified"));
            user.setUpdatedAt(LocalDateTime.now());
            User saved = userRepository.save(user);
            saved.setPassword("[HIDDEN]");
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** DELETE /api/admin/users/{id} */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id, java.security.Principal principal) {
        try {
            return userRepository.findById(id).map(user -> {
                if (principal != null && user.getEmail().equals(principal.getName())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Bạn không thể tự xoá tài khoản của chính mình."));
                }
                userRepository.deleteById(id);
                return ResponseEntity.ok(Map.of("message", "Đã xoá tài khoản"));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** PUT /api/admin/users/{id}/role */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable String id, @RequestBody Map<String, String> body, java.security.Principal principal) {
        String newRole = body.get("role");
        if (!List.of("student","teacher","admin").contains(newRole)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role không hợp lệ"));
        }
        return userRepository.findById(id).map(user -> {
            if (principal != null && user.getEmail().equals(principal.getName())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Bạn không thể tự thay đổi vai trò của chính mình."));
            }
            user.setRole(newRole);
            user.setUpdatedAt(LocalDateTime.now());
            User saved = userRepository.save(user);
            saved.setPassword("[HIDDEN]");
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** PUT /api/admin/users/{id}/lock */
    @PutMapping("/users/{id}/lock")
    public ResponseEntity<?> toggleLock(@PathVariable String id, @RequestBody Map<String, Object> body, java.security.Principal principal) {
        boolean lock = (Boolean) body.getOrDefault("locked", true);
        return userRepository.findById(id).map(user -> {
            if (lock && principal != null && user.getEmail().equals(principal.getName())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Bạn không thể tự khoá tài khoản của chính mình."));
            }
            user.setAccountLocked(lock);
            if (!lock) { user.setFailedLoginAttempts(0); user.setLockExpiry(null); }
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", lock ? "Đã khoá tài khoản" : "Đã mở khoá"));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─── Audit Logs ───────────────────────────────────────────────────────────

    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(@RequestParam(defaultValue = "100") int limit) {
        try {
            List<AuditLog> logs = auditLogRepository.findAll(Sort.by(Sort.Direction.DESC, "timestamp"));
            if (logs.size() > limit) logs = logs.subList(0, limit);
            var result = logs.stream().map(log -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", log.getId());
                map.put("userId", log.getUserId());
                map.put("email", log.getEmail());
                map.put("event", log.getEvent());
                map.put("ipAddress", normalizeDisplayIp(log.getIpAddress()));
                map.put("userAgent", ClientInfoUtil.getReadableDevice(log.getUserAgent()));
                map.put("timestamp", log.getTimestamp());
                map.put("success", log.isSuccess());
                map.put("details", log.getDetails());
                return map;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/audit-logs/summary")
    public ResponseEntity<?> getAuditSummary() {
        try {
            List<AuditLog> all = auditLogRepository.findAll();
            long total = all.size();
            long failures = all.stream().filter(l -> !l.isSuccess()).count();
            long loginSuccess = all.stream().filter(l -> "LOGIN".equals(l.getEvent()) && l.isSuccess()).count();
            Set<String> suspiciousIPs = all.stream()
                    .filter(l -> !l.isSuccess())
                    .map(AuditLog::getIpAddress)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            return ResponseEntity.ok(Map.of(
                "total", total,
                "failures", failures,
                "loginSuccess", loginSuccess,
                "suspiciousIpCount", suspiciousIPs.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Exams ─────────────────────────────────────────────────────────────────

    @GetMapping("/exams")
    public ResponseEntity<?> getAllExams() {
        try {
            return ResponseEntity.ok(examRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/exams/{id}")
    public ResponseEntity<?> deleteExam(@PathVariable String id) {
        try {
            if (!examRepository.existsById(id)) return ResponseEntity.notFound().build();
            examRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Đã xoá bài thi"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Settings ──────────────────────────────────────────────────────────────

    @Autowired
    private com.auracademic.backend.service.SettingService settingService;

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(settingService.getAllSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> settings) {
        settingService.updateSettings(settings);
        return ResponseEntity.ok(Map.of("message", "Đã lưu cấu hình hệ thống"));
    }

    @GetMapping("/settings/health")
    public ResponseEntity<?> getSettingsHealth() {
        Map<String, Object> result = new HashMap<>();
        
        // 1. Check SMTP
        boolean smtpConfigured = smtpHost != null && !smtpHost.isBlank() && smtpUsername != null && !smtpUsername.isBlank();
        result.put("smtpConfigured", smtpConfigured);
        result.put("smtpHost", smtpHost);
        result.put("smtpUsername", smtpConfigured && smtpUsername.contains("@") 
            ? smtpUsername.replaceAll("(?<=.{2}).(?=[^@]*?@)", "*") 
            : smtpUsername);
            
        boolean smtpConnected = false;
        String smtpError = null;
        if (smtpConfigured && mailSender instanceof JavaMailSenderImpl) {
            try {
                ((JavaMailSenderImpl) mailSender).testConnection();
                smtpConnected = true;
            } catch (Exception e) {
                smtpError = e.getMessage();
            }
        }
        result.put("smtpConnected", smtpConnected);
        result.put("smtpError", smtpError);

        // 2. Check Google Client ID
        boolean googleConfigured = googleClientId != null && !googleClientId.isBlank();
        result.put("googleConfigured", googleConfigured);
        result.put("googleClientId", googleConfigured && googleClientId.length() > 10 
            ? googleClientId.substring(0, 10) + "..." 
            : googleClientId);

        return ResponseEntity.ok(result);
    }

    @Autowired
    private com.auracademic.backend.service.GeminiService geminiService;
    @Autowired
    private com.auracademic.backend.service.GroqService groqService;

    @GetMapping("/ai-tokens/check")
    public ResponseEntity<?> checkAiTokens(@RequestParam String type) {
        if ("gemini".equalsIgnoreCase(type)) {
            return ResponseEntity.ok(geminiService.checkHealth());
        } else if ("groq".equalsIgnoreCase(type)) {
            return ResponseEntity.ok(groqService.checkHealth());
        }
        return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Invalid provider type"));
    }

    @Autowired
    private com.auracademic.backend.repository.RefreshTokenRepository refreshTokenRepository;

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions() {
        try {
            var tokens = refreshTokenRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
            var users = userRepository.findAll().stream()
                    .collect(Collectors.toMap(User::getId, u -> u));

            var result = tokens.stream().map(t -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", t.getId());
                map.put("userId", t.getUserId());
                map.put("token", t.getToken().substring(0, Math.min(t.getToken().length(), 10)) + "...");
                map.put("createdAt", t.getCreatedAt());
                map.put("expiresAt", t.getExpiresAt());
                map.put("deviceInfo", ClientInfoUtil.getReadableDevice(t.getDeviceInfo()));
                map.put("ipAddress", normalizeDisplayIp(t.getIpAddress()));
                map.put("expired", t.isExpired());

                User u = users.get(t.getUserId());
                if (u != null) {
                    map.put("email", u.getEmail());
                    map.put("fullName", u.getFullName());
                }
                return map;
            }).collect(Collectors.toList());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> revokeSession(@PathVariable String id) {
        try {
            refreshTokenRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Đã thu hồi phiên đăng nhập"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Teacher Verification Management ──────────────────────────────────────

    /** GET /api/admin/verification-requests?status=PENDING|VERIFIED|REJECTED|ALL */
    @GetMapping("/verification-requests")
    public ResponseEntity<?> getVerificationRequests(
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        try {
            List<Map<String, Object>> requests = userRepository.findAll().stream()
                .filter(u -> "teacher".equalsIgnoreCase(u.getRole()))
                .filter(u -> {
                    String s = u.getVerificationStatus() != null ? u.getVerificationStatus() : "STANDARD";
                    return "ALL".equalsIgnoreCase(status) || status.equalsIgnoreCase(s);
                })
                .sorted((a, b) -> {
                    if (a.getVerificationRequestedAt() == null) return 1;
                    if (b.getVerificationRequestedAt() == null) return -1;
                    return b.getVerificationRequestedAt().compareTo(a.getVerificationRequestedAt());
                })
                .map(u -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", u.getId());
                    map.put("fullName", u.getFullName());
                    map.put("email", u.getEmail());
                    map.put("verificationStatus", u.getVerificationStatus() != null ? u.getVerificationStatus() : "STANDARD");
                    map.put("verificationProofUrl", u.getVerificationProofUrl());
                    map.put("verificationProofType", u.getVerificationProofType());
                    map.put("verificationNote", u.getVerificationNote());
                    map.put("verificationRequestedAt", u.getVerificationRequestedAt());
                    map.put("verifiedAt", u.getVerifiedAt());
                    return map;
                })
                .collect(Collectors.toList());

            long pendingCount = userRepository.findAll().stream()
                .filter(u -> "teacher".equalsIgnoreCase(u.getRole()) && "PENDING".equals(u.getVerificationStatus()))
                .count();

            return ResponseEntity.ok(Map.of("requests", requests, "pendingCount", pendingCount));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/admin/verification-requests/{userId}/approve */
    @PostMapping("/verification-requests/{userId}/approve")
    public ResponseEntity<?> approveVerification(@PathVariable String userId) {
        return userRepository.findById(userId).map(user -> {
            if (!"teacher".equalsIgnoreCase(user.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Người dùng không phải giáo viên."));
            }
            user.setVerificationStatus("VERIFIED");
            user.setVerifiedAt(LocalDateTime.now());
            user.setVerificationNote(null);
            userRepository.save(user);

            try {
                Notification notification = new Notification();
                notification.setUserId(user.getId());
                notification.setTitle("Tài khoản đã được xác thực 🎉");
                notification.setContent("Chúc mừng! Tài khoản giáo viên của bạn đã được xác thực thành công. Toàn bộ tính năng đã được mở khóa.");
                notification.setType("VERIFICATION_APPROVED");
                notification.setRead(false);
                notification.setCreatedAt(LocalDateTime.now());
                notificationRepository.save(notification);
            } catch (Exception ignored) {}

            return ResponseEntity.ok(Map.of("message", "Đã duyệt tài khoản giáo viên.", "status", "VERIFIED"));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** POST /api/admin/verification-requests/{userId}/reject */
    @PostMapping("/verification-requests/{userId}/reject")
    public ResponseEntity<?> rejectVerification(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String note = body.getOrDefault("note", "Thông tin chứng minh không đủ điều kiện xác thực.");
        return userRepository.findById(userId).map(user -> {
            if (!"teacher".equalsIgnoreCase(user.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Người dùng không phải giáo viên."));
            }
            user.setVerificationStatus("REJECTED");
            user.setVerificationNote(note);
            userRepository.save(user);

            try {
                Notification notification = new Notification();
                notification.setUserId(user.getId());
                notification.setTitle("Yêu cầu xác thực chưa được chấp thuận");
                notification.setContent("Lý do: " + note + " Bạn có thể gửi lại yêu cầu với thông tin bổ sung.");
                notification.setType("VERIFICATION_REJECTED");
                notification.setRead(false);
                notification.setCreatedAt(LocalDateTime.now());
                notificationRepository.save(notification);
            } catch (Exception ignored) {}

            return ResponseEntity.ok(Map.of("message", "Đã từ chối yêu cầu xác thực.", "status", "REJECTED"));
        }).orElse(ResponseEntity.notFound().build());
    }

    private String normalizeDisplayIp(String ipAddress) {
        if ("0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)) {
            return "127.0.0.1";
        }
        return ipAddress;
    }
}
