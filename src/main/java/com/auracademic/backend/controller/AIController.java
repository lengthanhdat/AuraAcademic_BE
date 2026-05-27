package com.auracademic.backend.controller;

import com.auracademic.backend.model.AiJob;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.repository.AiJobRepository;
import com.auracademic.backend.service.AiProcessingService;
import com.auracademic.backend.service.LiteLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller xử lý các API liên quan đến AI sinh câu hỏi.
 *
 * Kiến trúc Fire-and-Poll:
 *   POST /generate-questions  → tạo AiJob, trả về jobId ngay lập tức (< 50ms)
 *   GET  /jobs/{jobId}        → frontend polling để kiểm tra tiến trình
 *   POST /chat-refine         → tinh chỉnh câu hỏi qua AI assistant (đồng bộ)
 */
@RestController
@RequestMapping("/api/ai")
public class AIController {

    private static final Logger log = LoggerFactory.getLogger(AIController.class);

    @Autowired
    private AiJobRepository aiJobRepository;

    @Autowired
    private AiProcessingService aiProcessingService;

    @Autowired
    private LiteLlmService liteLlmService;

    // ─────────────────────────────────────────────────────────────────
    // POST /api/ai/generate-questions
    // Trả về jobId NGAY LẬP TỨC, không chờ AI xử lý xong
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/generate-questions")
    public ResponseEntity<?> generateQuestions(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "count", required = false, defaultValue = "20") Integer count,
            @RequestParam(value = "questionCount", required = false) Integer questionCount) {

        int finalCount = (questionCount != null) ? questionCount : count;
        log.info("[API] Nhận yêu cầu tạo {} câu hỏi — file: {}", finalCount, file.getOriginalFilename());

        // 1. Validate file cơ bản
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File không được để trống."));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".pdf")
                && !filename.toLowerCase().endsWith(".docx")
                && !filename.toLowerCase().endsWith(".txt"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Định dạng file không được hỗ trợ. Hãy dùng PDF, DOCX hoặc TXT."));
        }

        // 2. Tạo AiJob với trạng thái PROCESSING và lưu vào MongoDB
        AiJob job = new AiJob();
        job.setStatus("PROCESSING");
        job.setCreatedAt(System.currentTimeMillis());
        job = aiJobRepository.save(job);
        final String jobId = job.getId();
        log.info("[API] Đã tạo AiJob [{}] — bắt đầu xử lý async", jobId);

        // 3. Đọc dữ liệu file đồng bộ trước khi chuyển sang thread bất đồng bộ (tránh FileNotFoundException)
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không thể đọc dữ liệu file."));
        }

        // 4. Kích hoạt xử lý bất đồng bộ (return ngay, không chờ)
        aiProcessingService.processJob(jobId, fileBytes, filename, finalCount);

        // 5. Trả về jobId cho frontend để polling
        return ResponseEntity.accepted().body(Map.of(
            "jobId", jobId,
            "status", "PROCESSING",
            "message", "Tài liệu đang được xử lý. Hãy polling endpoint /api/ai/jobs/" + jobId
        ));
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/ai/generate-from-prompt
    // Tạo câu hỏi từ chủ đề / lời nhắc tự do — không cần upload file
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/generate-from-prompt")
    public ResponseEntity<?> generateFromPrompt(@RequestBody Map<String, Object> body) {
        String topic      = (String) body.getOrDefault("topic", "");
        String difficulty = (String) body.getOrDefault("difficulty", "MEDIUM");
        String language   = (String) body.getOrDefault("language", "vi");
        int count         = body.get("count") instanceof Number n ? n.intValue() : 10;

        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui lòng nhập chủ đề hoặc mô tả đề thi."));
        }
        if (count < 1 || count > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Số câu hỏi phải trong khoảng 1 – 100."));
        }

        log.info("[API] Nhận yêu cầu tạo {} câu từ prompt: '{}' (độ khó: {}, ngôn ngữ: {})",
                count, topic, difficulty, language);

        // Tạo AiJob & kick off async
        AiJob job = new AiJob();
        job.setStatus("PROCESSING");
        job.setCreatedAt(System.currentTimeMillis());
        job = aiJobRepository.save(job);
        final String jobId = job.getId();

        aiProcessingService.processTopicJob(jobId, topic, difficulty, language, count);

        return ResponseEntity.accepted().body(Map.of(
            "jobId",   jobId,
            "status",  "PROCESSING",
            "message", "AI đang biên soạn đề thi. Polling tại /api/ai/jobs/" + jobId
        ));
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/ai/jobs/{jobId}
    // Frontend gọi endpoint này định kỳ (polling) để lấy kết quả
    // ─────────────────────────────────────────────────────────────────
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        return aiJobRepository.findById(jobId)
            .map(job -> {
                // Trả về toàn bộ thông tin job (status + questions nếu DONE)
                return ResponseEntity.ok(Map.of(
                    "jobId",         job.getId(),
                    "status",        job.getStatus(),
                    "questions",     job.getQuestions()     != null ? job.getQuestions()     : List.of(),
                    "extractedText", job.getExtractedText() != null ? job.getExtractedText() : "",
                    "extractedImages", job.getExtractedImages() != null ? job.getExtractedImages() : List.of(),
                    "errorMessage",  job.getErrorMessage()  != null ? job.getErrorMessage()  : ""
                ));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }


    @PostMapping("/choose-correct-answer")
    public ResponseEntity<?> chooseCorrectAnswer(@RequestBody Map<String, Object> payload) {
        try {
            String question = String.valueOf(payload.getOrDefault("question", "")).trim();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = (List<Map<String, Object>>) payload.getOrDefault("options", List.of());

            if (question.isBlank() || options.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing question or options."));
            }

            String prompt = buildChooseCorrectPrompt(question, options);
            String rawAnswer;
            String provider = "litellm";
            rawAnswer = liteLlmService.chooseCorrectAnswer(question, options);

            String label = parseAnswerLabel(rawAnswer);
            if (label == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "AI did not return a valid answer label.",
                    "rawAnswer", rawAnswer != null ? rawAnswer : "",
                    "provider", provider
                ));
            }

            String optionId = findOptionIdByLabel(options, label);
            if (optionId == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "AI returned a label that does not exist in options.",
                    "correctLabel", label,
                    "rawAnswer", rawAnswer != null ? rawAnswer : "",
                    "provider", provider
                ));
            }

            return ResponseEntity.ok(Map.of(
                "correctLabel", label,
                "correctOptionId", optionId,
                "rawAnswer", rawAnswer != null ? rawAnswer : "",
                "provider", provider
            ));
        } catch (Exception e) {
            log.error("[API] choose-correct-answer failed: ", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "AI answer selection failed: " + e.getMessage()));
        }
    }

    private String buildChooseCorrectPrompt(String question, List<Map<String, Object>> options) {
        StringBuilder optionLines = new StringBuilder();
        for (Map<String, Object> option : options) {
            String label = String.valueOf(option.getOrDefault("label", "")).trim().toUpperCase();
            String text = String.valueOf(option.getOrDefault("text", "")).trim();
            if (!label.isBlank() && !text.isBlank()) {
                optionLines.append(label).append(". ").append(text).append("\n");
            }
        }

        return "You are an expert teacher. Choose exactly one correct answer for this multiple-choice question.\n"
            + "Return only valid JSON, no markdown, no explanation. Required schema: {\"correctLabel\":\"A\"}.\n"
            + "The correctLabel must be one of the option labels shown below.\n\n"
            + "Question: " + question + "\n"
            + optionLines;
    }

    private String parseAnswerLabel(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return null;
        }

        String cleaned = rawAnswer.trim()
            .replaceFirst("(?is)^```json\\s*", "")
            .replaceFirst("(?is)^```\\s*", "")
            .replaceFirst("(?is)```$", "")
            .trim();

        Matcher jsonLike = Pattern.compile("(?i)\\\"?(?:correctLabel|correctOption|answer|label)\\\"?\\s*[:=]\\s*\\\"?([A-D])\\\"?").matcher(cleaned);
        if (jsonLike.find()) {
            return jsonLike.group(1).toUpperCase();
        }

        Matcher vi = Pattern.compile("(?i)(?:dap\\s*an|answer|label)\\s*[:=]\\s*([A-D])\\b").matcher(cleaned);
        if (vi.find()) {
            return vi.group(1).toUpperCase();
        }

        Matcher standalone = Pattern.compile("(?i)^\\s*([A-D])\\s*\\.?,?\\s*$").matcher(cleaned);
        if (standalone.find()) {
            return standalone.group(1).toUpperCase();
        }

        return null;
    }

    private String findOptionIdByLabel(List<Map<String, Object>> options, String label) {
        for (Map<String, Object> option : options) {
            String optionLabel = String.valueOf(option.getOrDefault("label", "")).trim().toUpperCase();
            if (label.equals(optionLabel)) {
                return String.valueOf(option.getOrDefault("id", label.toLowerCase())).trim();
            }
        }
        return null;
    }
    // ─────────────────────────────────────────────────────────────────
    // POST /api/ai/chat-refine
    // Tinh chỉnh / thêm câu hỏi qua AI assistant (đồng bộ, giữ nguyên)
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/chat-refine")
    public ResponseEntity<?> chatRefine(@RequestBody Map<String, Object> payload) {
        try {
            String command      = (String) payload.get("command");
            String documentText = (String) payload.get("documentText");
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) payload.getOrDefault("images", List.of());

            log.info("[API] Chat-refine — lệnh: \"{}\" (kèm {} ảnh)", command, images.size());

            List<Question> updated = liteLlmService.refineQuestions(command, documentText, images);
            return ResponseEntity.ok(Map.of("questions", updated));

        } catch (Exception e) {
            log.error("[API] Chat-refine thất bại nghiêm trọng: ", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Lỗi trợ lý AI: " + e.getMessage()));
        }
    }
    @PostMapping("/explain")
    public ResponseEntity<?> explainAnswer(@RequestBody Map<String, String> payload) {
        try {
            String question = String.valueOf(payload.getOrDefault("question", "")).trim();
            String selected = String.valueOf(payload.getOrDefault("selected", "")).trim();
            String correct = String.valueOf(payload.getOrDefault("correct", "")).trim();

            if (question.isBlank() || correct.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing question or correct answer."));
            }

            String prompt = "Bạn là trợ lý học tập cho học sinh. "
                + "Giải thích ngắn gọn, dễ hiểu bằng tiếng Việt vì sao đáp án đúng là đáp án đã cho. "
                + "Không lan man, không nhắc tới việc bạn là AI.\n\n"
                + "Câu hỏi: " + question + "\n"
                + "Học sinh chọn: " + (selected.isBlank() ? "Chưa chọn" : selected) + "\n"
                + "Đáp án đúng: " + correct + "\n\n"
                + "Hãy giải thích trong tối đa 120 từ.";

            String explanation;
            String provider = "litellm";
            explanation = liteLlmService.generateChatResponse(prompt);

            if (explanation == null || explanation.isBlank()) {
                return ResponseEntity.unprocessableEntity().body(Map.of("error", "AI did not return an explanation.", "provider", provider));
            }

            return ResponseEntity.ok(Map.of("explanation", explanation.trim(), "provider", provider));
        } catch (Exception e) {
            log.error("[API] explain failed: ", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "AI explanation failed: " + e.getMessage()));
        }
    }

}
