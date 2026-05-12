package com.auracademic.backend.controller;

import com.auracademic.backend.model.ViolationLog;
import com.auracademic.backend.repository.ViolationLogRepository;
import com.auracademic.backend.service.ExamEventService;
import com.auracademic.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")

public class ViolationController {

    @Autowired
    private ViolationLogRepository violationRepository;

    @Autowired
    private ExamEventService examEventService;

    @Autowired
    private SettingService settingService;

    @PostMapping("/{code}/violation")
    public ResponseEntity<?> reportViolation(@PathVariable String code, @RequestBody Map<String, String> body) {
        if (!settingService.getBoolean(SettingService.ENABLE_AI_PROCTOR, true)
                || !settingService.getBoolean(SettingService.AUTO_DETECT_CHEAT, true)) {
            return ResponseEntity.ok(Map.of(
                    "ignored", true,
                    "message", "AI proctoring or auto cheat detection is disabled by admin settings"
            ));
        }

        String studentId   = body.get("studentId");
        String studentName = body.get("studentName");
        String type        = body.get("type");
        String videoUrl    = body.get("videoUrl");    // Legacy support
        String videoBase64 = body.get("videoBase64"); // Mới: video Base64

        if (studentId == null || studentId.isBlank() || type == null || type.isBlank()) {
            return ResponseEntity.badRequest().body("Thiếu dữ liệu vi phạm (studentId, type)");
        }

        ViolationLog log = new ViolationLog(code.toUpperCase(), studentId, studentName, type, videoUrl, videoBase64, System.currentTimeMillis());
        ViolationLog savedLog = violationRepository.save(log);

        // Phát tín hiệu Realtime cho Giáo viên
        examEventService.broadcast(code.toUpperCase(), "violation", savedLog);

        return ResponseEntity.ok(savedLog);
    }

    @GetMapping("/{code}/violations")
    public ResponseEntity<?> getViolations(@PathVariable String code) {
        List<ViolationLog> logs = violationRepository.findByExamCodeOrderByTimestampDesc(code.toUpperCase());
        return ResponseEntity.ok(logs);
    }
}
