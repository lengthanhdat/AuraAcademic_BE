package com.auracademic.backend.controller;

import com.auracademic.backend.model.AuditLog;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.AuditLogRepository;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.util.ClientInfoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Users CRUD ───────────────────────────────────────────────────────────

    /** GET /api/admin/users — danh sách tất cả người dùng */
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

    /** GET /api/admin/users/{id} — chi tiết một người dùng */
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable String id) {
        return userRepository.findById(id)
                .map(u -> { u.setPassword("[HIDDEN]"); return ResponseEntity.ok(u); })
                .orElse(ResponseEntity.notFound().build());
    }

    /** POST /api/admin/users — tạo người dùng mới */
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
            user.setPassword("[TEMP]"); // admin nên gửi link reset
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

    /** PUT /api/admin/users/{id} — cập nhật thông tin người dùng */
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
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        try {
            if (!userRepository.existsById(id)) return ResponseEntity.notFound().build();
            userRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("message", "Đã xoá tài khoản"));
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

    /** PUT /api/admin/users/{id}/lock — khoá / mở khoá tài khoản */
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

    /** GET /api/admin/audit-logs?limit=50 */
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

    /** GET /api/admin/audit-logs/summary — thống kê nhanh audit */
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

    /** GET /api/admin/exams — danh sách bài thi */
    @GetMapping("/exams")
    public ResponseEntity<?> getAllExams() {
        try {
            return ResponseEntity.ok(examRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** DELETE /api/admin/exams/{id} */
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

    private String normalizeDisplayIp(String ipAddress) {
        if ("0:0:0:0:0:0:0:1".equals(ipAddress) || "::1".equals(ipAddress)) {
            return "127.0.0.1";
        }
        return ipAddress;
    }
}
