package com.auracademic.backend.controller;

import com.auracademic.backend.model.Exam;
import com.auracademic.backend.model.ExamResult;
import com.auracademic.backend.model.ExamVersion;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.service.ActiveParticipantService;
import com.auracademic.backend.service.ExamEventService;
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
@CrossOrigin(origins = "http://localhost:3000") // TODO: use configured CORS instead of hardcoding for production
public class ExamController {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ExamResultRepository resultRepository;

    @Autowired
    private ActiveParticipantService activeParticipantService;

    @Autowired
    private ExamEventService examEventService;

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
        String studentId = body.get("studentId");
        if (studentId != null && !studentId.isBlank()) {
            activeParticipantService.heartbeat(code, studentId);
            // Broadcast cập nhật số người chờ
            long count = activeParticipantService.getActiveCount(code);
            examEventService.broadcast(code, "count", Map.of("activeCount", count));
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{code}/active-count")
    public ResponseEntity<?> getActiveCount(@PathVariable String code) {
        long count = activeParticipantService.getActiveCount(code);
        return ResponseEntity.ok(Map.of("activeCount", count));
    }

    @PostMapping("/{code}/leave")
    public ResponseEntity<?> leaveRoom(@PathVariable String code, @RequestBody Map<String, String> body) {
        String studentId = body.get("studentId");
        if (studentId != null && !studentId.isBlank()) {
            activeParticipantService.removeParticipant(code, studentId);
            // Broadcast cập nhật số người chờ
            long count = activeParticipantService.getActiveCount(code);
            examEventService.broadcast(code, "count", Map.of("activeCount", count));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping
    public ResponseEntity<?> createExam(@RequestBody Exam exam) {
        try {
            if (exam.getStatus() == null) {
                exam.setStatus("DRAFT");
            }
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
                    if ("DRAFT".equals(exam.getStatus())) {
                        return ResponseEntity.badRequest().body("Phòng thi này chưa được mở.");
                    }
                    if ("FINISHED".equals(exam.getStatus())) {
                        return ResponseEntity.badRequest().body("Phòng thi này đã kết thúc.");
                    }
                    Map<String, Object> info = new HashMap<>();
                    info.put("title", exam.getTitle());
                    info.put("duration", exam.getDuration());
                    info.put("status", exam.getStatus());
                    info.put("accessCode", exam.getAccessCode());
                    info.put("startTime", exam.getStartTime());
                    return ResponseEntity.ok(info);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/join/{code}")
    public ResponseEntity<?> joinExam(@PathVariable String code) {
        return examRepository.findByAccessCode(code.toUpperCase())
                .map(exam -> {
                    if (!"STARTED".equals(exam.getStatus()) && !"PUBLISHED".equals(exam.getStatus())) {
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
                    
                    // Trả về object chứa cả thông tin chung của kỳ thi
                    Map<String, Object> response = new HashMap<>();
                    response.put("title", exam.getTitle());
                    response.put("duration", exam.getDuration());
                    response.put("startTime", exam.getStartTime()); // Gửi mốc bắt đầu của cả phòng
                    response.put("versionCode", selectedVersion.getVersionCode());
                    response.put("questions", selectedVersion.getQuestions());
                    response.put("extractedImages", exam.getExtractedImages());
                    
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getExamById(@PathVariable String id) {
        return examRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateExam(@PathVariable String id, @RequestBody Exam exam) {
        try {
            exam.setId(id);
            Exam updatedExam = examRepository.save(exam);
            return ResponseEntity.ok(updatedExam);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error updating exam: " + e.getMessage());
        }
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
     * Giao vien bat dau ky thi (WAITING -> STARTED), ghi startTime
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
                    "message", "Ky thi da bat dau.",
                    "startTime", exam.getStartTime()
                ));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error starting exam: " + e.getMessage());
        }
    }

    /**
     * Giao vien dong phong thi thu cong truoc khi het gio
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
                return ResponseEntity.ok(Map.of("message", "Phong thi da duoc dong thanh cong."));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error closing exam: " + e.getMessage());
        }
    }

    /**
     * Doi trang thai exam FINISHED thanh PUBLISHED lai (mo lai phong thi)
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<?> reopenExam(@PathVariable String id) {
        try {
            return examRepository.findById(id).map(exam -> {
                exam.setStatus("PUBLISHED");
                examRepository.save(exam);
                return ResponseEntity.ok(Map.of("message", "Phong thi da duoc mo lai."));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error reopening exam: " + e.getMessage());
        }
    }
}
