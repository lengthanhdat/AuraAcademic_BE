package com.auracademic.backend.controller;

import com.auracademic.backend.model.Exam;
import com.auracademic.backend.model.ExamResult;
import com.auracademic.backend.model.ExamVersion;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.service.ActiveParticipantService;
import com.auracademic.backend.service.ExamEventService;
import com.auracademic.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")

public class ExamController {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ExamResultRepository resultRepository;

    @Autowired
    private ActiveParticipantService activeParticipantService;

    @Autowired
    private ExamEventService examEventService;

    @Autowired
    private SettingService settingService;

    /**
     * SSE endpoint — client kết nối để nhận sự kiện theo thời gian thực
     */
    @GetMapping(value = "/{code}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String code) {
        return examEventService.subscribe(code);
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submitResult(@RequestBody ExamResult result) {
        try {
            if (resultRepository.existsByStudentIdAndExamId(result.getStudentId(), result.getExamId())) {
                return ResponseEntity.badRequest().body("Bạn đã hoàn thành bài thi này rồi.");
            }
            result.setSubmittedAt(System.currentTimeMillis());
            ExamResult savedResult = resultRepository.save(result);
            activeParticipantService.removeParticipant(result.getExamId(), result.getStudentId());
            // Broadcast kết quả mới cho giáo viên
            examEventService.broadcast(result.getExamId(), "result", savedResult);
            return ResponseEntity.ok(savedResult);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error saving result: " + e.getMessage());
        }
    }

    @PostMapping("/{code}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String code, @RequestBody Map<String, String> body) {
        // Auto-start trigger
        examRepository.findByAccessCode(code.toUpperCase()).ifPresent(exam -> {
            if ("PUBLISHED".equals(exam.getStatus()) && exam.getScheduledStartTime() != null && System.currentTimeMillis() >= exam.getScheduledStartTime()) {
                exam.setStatus("STARTED");
                exam.setStartTime(exam.getScheduledStartTime());
                examRepository.save(exam);
                examEventService.broadcast(exam.getAccessCode(), "status",
                    Map.of("status", "STARTED", "startTime", exam.getStartTime()));
            }
        });

        String studentId = body.get("studentId");
        String status = body.getOrDefault("status", "LOBBY"); // "LOBBY" hoặc "EXAM"
        if (studentId != null && !studentId.isBlank()) {
            activeParticipantService.heartbeat(code, studentId, status);
            // Broadcast cập nhật số người cho giáo viên
            long lobbyCount = activeParticipantService.getLobbyCount(code);
            long examCount  = activeParticipantService.getExamCount(code);
            examEventService.broadcast(code, "count", Map.of(
                "activeCount", lobbyCount + examCount,
                "lobbyCount", lobbyCount,
                "examCount",  examCount
            ));
        }
        // Retrieve current exam status to return as fallback
        String currentStatus = examRepository.findByAccessCode(code.toUpperCase())
            .map(Exam::getStatus)
            .orElse("UNKNOWN");

        return ResponseEntity.ok(Map.of("status", currentStatus));
    }

    @GetMapping("/{code}/active-count")
    public ResponseEntity<?> getActiveCount(@PathVariable String code) {
        long lobby = activeParticipantService.getLobbyCount(code);
        long exam  = activeParticipantService.getExamCount(code);
        return ResponseEntity.ok(Map.of(
            "activeCount", lobby + exam,
            "lobbyCount",  lobby,
            "examCount",   exam
        ));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<?> leaveRoom(@PathVariable String code, @RequestBody Map<String, String> body) {
        String studentId = body.get("studentId");
        if (studentId != null && !studentId.isBlank()) {
            activeParticipantService.removeParticipant(code, studentId);
            long lobbyCount = activeParticipantService.getLobbyCount(code);
            long examCount  = activeParticipantService.getExamCount(code);
            examEventService.broadcast(code, "count", Map.of(
                "activeCount", lobbyCount + examCount,
                "lobbyCount", lobbyCount,
                "examCount",  examCount
            ));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public ResponseEntity<?> createExam(@RequestBody Exam exam) {
        try {
            if (exam.getStatus() == null) {
                exam.setStatus("DRAFT");
            }
            applyGlobalExamSettings(exam);
            // Chỉ set startTime khi status là STARTED (giáo viên bấm nút Bắt đầu)
            // WAITING = đã tạo phòng nhưng chưa bắt đầu
            // Tự động tạo mã phòng nếu chưa có
            if (exam.getAccessCode() == null || exam.getAccessCode().isEmpty()) {
                exam.setAccessCode(java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            }
            Exam savedExam = examRepository.save(exam);
            return ResponseEntity.ok(savedExam);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error creating exam: " + e.getMessage());
        }
    }

    @GetMapping("/results/student/{studentId}")
    public ResponseEntity<?> getStudentResults(@PathVariable String studentId) {
        try {
            List<ExamResult> results = resultRepository.findByStudentId(studentId);
            // Bổ sung tên bài thi nếu kết quả cũ chưa có
            for (ExamResult r : results) {
                if (r.getExamTitle() == null || r.getExamTitle().isEmpty()) {
                    // Tìm theo accessCode (examId trong Result đang lưu accessCode)
                    examRepository.findByAccessCode(r.getExamId())
                        .ifPresent(ex -> r.setExamTitle(ex.getTitle()));
                }
            }
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching results: " + e.getMessage());
        }
    }

    /**
     * Học sinh check trạng thái phòng trong khi chờ — chỉ trả về thông tin cơ bản, không có câu hỏi
     */
    @GetMapping("/lobby/{code}")
    public ResponseEntity<?> getLobbyInfo(@PathVariable String code) {
        return examRepository.findByAccessCode(code.toUpperCase())
                .map(exam -> {
                    // Auto-start logic
                    if ("PUBLISHED".equals(exam.getStatus()) && exam.getScheduledStartTime() != null && System.currentTimeMillis() >= exam.getScheduledStartTime()) {
                        exam.setStatus("STARTED");
                        exam.setStartTime(exam.getScheduledStartTime());
                        examRepository.save(exam);
                        examEventService.broadcast(exam.getAccessCode(), "status",
                            Map.of("status", "STARTED", "startTime", exam.getStartTime()));
                    }

                    if ("DRAFT".equals(exam.getStatus())) {
                        return ResponseEntity.badRequest().body("Phòng thi này chưa được mở.");
                    }
                    if ("FINISHED".equals(exam.getStatus()) || "COMPLETED".equals(exam.getStatus())) {
                        return ResponseEntity.badRequest().body("Phòng thi này đã kết thúc.");
                    }
                    Map<String, Object> info = new HashMap<>();
                    info.put("title", exam.getTitle());
                    info.put("duration", exam.getDuration());
                    info.put("status", exam.getStatus());
                    info.put("accessCode", exam.getAccessCode());
                    info.put("startTime", exam.getStartTime());
                    info.put("scheduledStartTime", exam.getScheduledStartTime());
                    info.put("aiProctoring", isEffectiveAiProctoring(exam));
                    info.put("autoDetectCheat", settingService.getBoolean(SettingService.AUTO_DETECT_CHEAT, true));
                    return ResponseEntity.ok(info);
                })
                .orElse(ResponseEntity.status(404).body("Mã phòng thi không tồn tại."));
    }

    @GetMapping("/join/{code}")
    public ResponseEntity<?> joinExam(@PathVariable String code) {
        return examRepository.findByAccessCode(code.toUpperCase())
                .map(exam -> {
                    // Auto-start logic
                    if ("PUBLISHED".equals(exam.getStatus()) && exam.getScheduledStartTime() != null && System.currentTimeMillis() >= exam.getScheduledStartTime()) {
                        exam.setStatus("STARTED");
                        exam.setStartTime(exam.getScheduledStartTime());
                        examRepository.save(exam);
                        examEventService.broadcast(exam.getAccessCode(), "status",
                            Map.of("status", "STARTED", "startTime", exam.getStartTime()));
                    }

                    // Chỉ cho phép vào thi khi GV đã nhấn "Bắt đầu thi" (STARTED)
                    if (!"STARTED".equals(exam.getStatus())) {
                        if ("PUBLISHED".equals(exam.getStatus())) {
                            return ResponseEntity.badRequest().body(
                                "Giáo viên chưa bắt đầu buổi thi. Vui lòng chờ trong phòng chờ.");
                        }
                        return ResponseEntity.badRequest().body("Kỳ thi này chưa bắt đầu hoặc đã kết thúc.");
                    }
                    if (exam.getStartTime() != null) {
                        long endTime = exam.getStartTime() + (exam.getDuration() * 60 * 1000L);
                        if (System.currentTimeMillis() > endTime) {
                            return ResponseEntity.badRequest().body("Kỳ thi này đã kết thúc do hết thời gian làm bài.");
                        }
                    }
                    // Bốc ngẫu nhiên 1 mã đề cho học sinh
                    int versionIdx = (int) (Math.random() * exam.getVersions().size());
                    ExamVersion selectedVersion = exam.getVersions().get(versionIdx);
                    
                    Map<String, Object> response = new HashMap<>();
                    response.put("title", exam.getTitle());
                    response.put("duration", exam.getDuration());
                    response.put("startTime", exam.getStartTime());
                    response.put("versionCode", selectedVersion.getVersionCode());
                    response.put("questions", selectedVersion.getQuestions());
                    response.put("extractedImages", exam.getExtractedImages()); // Cần thiết để hiển thị ảnh trong câu hỏi
                    response.put("aiProctoring", isEffectiveAiProctoring(exam));
                    response.put("autoDetectCheat", settingService.getBoolean(SettingService.AUTO_DETECT_CHEAT, true));
                    
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getExamById(@PathVariable String id) {
        return examRepository.findById(id)
                .map(exam -> {
                    // Auto-start logic: Chuyển sang STARTED nếu thời gian đã điểm
                    if ("PUBLISHED".equals(exam.getStatus()) && exam.getScheduledStartTime() != null && System.currentTimeMillis() >= exam.getScheduledStartTime()) {
                        exam.setStatus("STARTED");
                        exam.setStartTime(exam.getScheduledStartTime());
                        examRepository.save(exam);
                        // Thông báo realtime cho tất cả client (giáo viên & học sinh)
                        examEventService.broadcast(exam.getAccessCode(), "status",
                            Map.of("status", "STARTED", "startTime", exam.getStartTime()));
                    }
                    applyGlobalExamSettings(exam);
                    return ResponseEntity.ok(exam);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateExam(@PathVariable String id, @RequestBody Exam exam) {
        try {
            exam.setId(id);
            // Bảo toàn accessCode từ document cũ nếu payload không gửi lên
            if (exam.getAccessCode() == null || exam.getAccessCode().isEmpty()) {
                examRepository.findById(id).ifPresent(existing -> {
                    if (existing.getAccessCode() != null && !existing.getAccessCode().isEmpty()) {
                        exam.setAccessCode(existing.getAccessCode());
                    } else {
                        // Tạo mới nếu cả hai đều null
                        exam.setAccessCode(java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase());
                    }
                });
            }
            applyGlobalExamSettings(exam);
            Exam updatedExam = examRepository.save(exam);
            return ResponseEntity.ok(updatedExam);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error updating exam: " + e.getMessage());
        }
    }

    /**
     * Tạo/làm mới mã phòng thi cho các đề chưa có mã
     */
    @PostMapping("/{id}/generate-code")
    public ResponseEntity<?> generateAccessCode(@PathVariable String id) {
        return examRepository.findById(id).map(exam -> {
            String newCode = java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            exam.setAccessCode(newCode);
            examRepository.save(exam);
            return ResponseEntity.ok(Map.of("accessCode", newCode));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteExam(@PathVariable String id) {
        try {
            examRepository.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error deleting exam: " + e.getMessage());
        }
    }

    @GetMapping("/{accessCode}/results")
    public ResponseEntity<?> getExamResultsByAccessCode(@PathVariable String accessCode) {
        try {
            List<ExamResult> results = resultRepository.findByExamId(accessCode);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching results: " + e.getMessage());
        }
    }

    @GetMapping("/teacher/{teacherId}")
    public ResponseEntity<?> getTeacherExams(@PathVariable String teacherId) {
        try {
            List<Exam> exams = examRepository.findByTeacherId(teacherId);
            for (Exam exam : exams) {
                applyGlobalExamSettings(exam);
                if (exam.getAccessCode() != null) {
                    long count = resultRepository.countByExamId(exam.getAccessCode());
                    exam.setSubmissionCount(count);
                }
            }
            return ResponseEntity.ok(exams);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching exams: " + e.getMessage());
        }
    }

    /**
     * Giáo viên bắt đầu kỳ thi (WAITING -> STARTED), ghi startTime
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<?> startExam(@PathVariable String id) {
        try {
            return examRepository.findById(id).map(exam -> {
                exam.setStatus("STARTED");
                exam.setStartTime(System.currentTimeMillis());
                examRepository.save(exam);
                // Broadcast đến tất cả học sinh trong phòng chờ — tự động chuyển vào thi
                examEventService.broadcast(exam.getAccessCode(), "status",
                    Map.of("status", "STARTED", "startTime", exam.getStartTime()));
                return ResponseEntity.ok(Map.of(
                    "message", "Kỳ thi đã bắt đầu.",
                    "startTime", exam.getStartTime()
                ));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error starting exam: " + e.getMessage());
        }
    }

    /**
     * Giáo viên đóng phòng thi thủ công trước khi hết giờ
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<?> closeExam(@PathVariable String id) {
        try {
            return examRepository.findById(id).map(exam -> {
                exam.setStatus("FINISHED");
                examRepository.save(exam);
                // Broadcast cho tất cả biết phòng đã đóng
                examEventService.broadcast(exam.getAccessCode(), "status",
                    Map.of("status", "FINISHED"));
                return ResponseEntity.ok(Map.of("message", "Phòng thi đã được đóng thành công."));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error closing exam: " + e.getMessage());
        }
    }

    /**
     * Hệ thống tự động kết thúc khi hết giờ
     */
    @PostMapping("/{id}/finish")
    public ResponseEntity<?> finishExam(@PathVariable String id) {
        try {
            return examRepository.findById(id).map(exam -> {
                exam.setStatus("COMPLETED");
                examRepository.save(exam);
                // Broadcast cho tất cả biết bài thi đã kết thúc
                examEventService.broadcast(exam.getAccessCode(), "status",
                    Map.of("status", "COMPLETED"));
                return ResponseEntity.ok(Map.of("message", "Bài thi đã kết thúc tự động."));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error finishing exam: " + e.getMessage());
        }
    }

    /**
     * Đổi trạng thái exam FINISHED thành PUBLISHED lại (mở lại phòng thi)
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<?> reopenExam(@PathVariable String id) {
        try {
            return examRepository.findById(id).map(exam -> {
                exam.setStatus("PUBLISHED");
                examRepository.save(exam);
                return ResponseEntity.ok(Map.of("message", "Phòng thi đã được mở lại."));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error reopening exam: " + e.getMessage());
        }
    }

    private boolean isEffectiveAiProctoring(Exam exam) {
        return settingService.getBoolean(SettingService.ENABLE_AI_PROCTOR, true) && exam.isAiProctoring();
    }

    private void applyGlobalExamSettings(Exam exam) {
        if (!settingService.getBoolean(SettingService.ENABLE_AI_PROCTOR, true)) {
            exam.setAiProctoring(false);
        }
    }
}
