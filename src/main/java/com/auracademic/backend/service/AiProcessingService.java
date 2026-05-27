package com.auracademic.backend.service;

import com.auracademic.backend.model.AiJob;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.repository.AiJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrator bất đồng bộ cho pipeline xử lý AI (Fire-and-Poll pattern).
 *
 * Pipeline:
 *   1. Nhận request → tạo AiJob(PROCESSING) → trả về jobId ngay (<50ms)
 *   2. Xử lý ngầm: DocumentExtractorService → LiteLlmService → lưu kết quả
 *   3. Frontend polling GET /api/ai/jobs/{jobId} cho đến khi DONE / FAILED
 *
 * Fallback & Load Balancing được xử lý hoàn toàn bởi LiteLLM Proxy:
 *   - Không còn isQuotaError() hay try-catch Gemini → Groq thủ công ở đây.
 *   - LiteLLM tự động xoay vòng qua nhiều API Keys và chuyển model khi cần.
 */
@Service
public class AiProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);

    @Autowired
    private AiJobRepository aiJobRepository;

    @Autowired
    private DocumentExtractorService documentExtractorService;

    @Autowired
    private LiteLlmService liteLlmService;

    /**
     * Xử lý toàn bộ pipeline AI trong thread riêng biệt (Async).
     * Tài liệu upload → trích xuất → LiteLLM sinh câu hỏi → lưu MongoDB.
     *
     * @param jobId     ID của AiJob đã tạo với status=PROCESSING
     * @param fileBytes Dữ liệu file (đã đọc đồng bộ trước khi gọi @Async)
     * @param filename  Tên file gốc
     * @param count     Số câu hỏi cần tạo
     */
    @Async
    public CompletableFuture<Void> processJob(String jobId, byte[] fileBytes, String filename, int count) {
        long startTime = System.currentTimeMillis();
        log.info("[AiJob:{}] Bắt đầu xử lý — file: {}, số câu: {}", jobId, filename, count);

        try {
            // ── Bước 1: Trích xuất văn bản và hình ảnh từ tài liệu ──────────────
            log.info("[AiJob:{}] Đang trích xuất nội dung từ file...", jobId);
            var content = documentExtractorService.extractContent(fileBytes, filename);
            String extractedText = (String) content.get("text");
            @SuppressWarnings("unchecked")
            List<String> extractedImages = (List<String>) content.getOrDefault("images", List.of());

            if ((extractedText == null || extractedText.trim().length() < 10) && extractedImages.isEmpty()) {
                failJob(jobId, "Nội dung tài liệu quá ngắn hoặc không đọc được.");
                return CompletableFuture.completedFuture(null);
            }
            log.info("[AiJob:{}] Trích xuất xong — {} ký tự văn bản, {} hình ảnh", jobId, extractedText.length(), extractedImages.size());

            // ── Bước 2: Gọi LiteLLM Proxy sinh câu hỏi ─────────────────────────
            // LiteLLM tự xử lý Load Balancing & Fallback — không cần code thêm ở đây
            log.info("[AiJob:{}] Đang gọi LiteLLM Proxy để sinh câu hỏi...", jobId);
            List<Question> questions;
            if (!extractedImages.isEmpty()) {
                questions = liteLlmService.generateQuestionsWithImages(extractedText, extractedImages, count);
            } else {
                questions = liteLlmService.generateQuestions(extractedText, count);
            }

            // ── Bước 3: Lưu kết quả → DONE ───────────────────────────────────────
            long duration = System.currentTimeMillis() - startTime;
            AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy job: " + jobId));

            job.setStatus("DONE");
            job.setQuestions(questions);
            job.setExtractedText(extractedText);
            job.setExtractedImages(extractedImages);
            job.setProvider("LiteLLM Proxy (Auto Load Balanced)");
            job.setProcessingTimeMs(duration);
            aiJobRepository.save(job);

            log.info("[AiJob:{}] Hoàn thành — {} câu hỏi trong {}ms", jobId, questions.size(), duration);

        } catch (Exception e) {
            log.error("[AiJob:{}] Thất bại — {}", jobId, e.getMessage(), e);
            failJob(jobId, buildUserFriendlyError(e));
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Xử lý pipeline AI sinh câu hỏi từ chủ đề (không cần tài liệu).
     * Sử dụng cùng cơ chế Async Job / Polling như processJob.
     *
     * @param jobId      ID của AiJob đã tạo với status=PROCESSING
     * @param topic      Chủ đề hoặc prompt mô tả đề thi
     * @param difficulty Mức độ khó: EASY | MEDIUM | HARD | EXPERT
     * @param language   Ngôn ngữ: "vi" | "en"
     * @param count      Số câu hỏi cần tạo
     */
    @Async
    public CompletableFuture<Void> processTopicJob(String jobId, String topic, String difficulty, String language, int count) {
        long startTime = System.currentTimeMillis();
        log.info("[AiJob:{}] [Topic] Bắt đầu — chủ đề: '{}', độ khó: {}, số câu: {}", jobId, topic, difficulty, count);

        try {
            log.info("[AiJob:{}] [Topic] Đang gọi LiteLLM Proxy...", jobId);
            List<Question> questions = liteLlmService.generateByTopic(topic, difficulty, language, count);

            long duration = System.currentTimeMillis() - startTime;
            AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy job: " + jobId));

            job.setStatus("DONE");
            job.setQuestions(questions);
            job.setExtractedText("[Generated from topic] " + topic);
            job.setExtractedImages(List.of());
            job.setProvider("LiteLLM Proxy (Auto Load Balanced)");
            job.setProcessingTimeMs(duration);
            aiJobRepository.save(job);

            log.info("[AiJob:{}] [Topic] Hoàn thành — {} câu hỏi trong {}ms", jobId, questions.size(), duration);

        } catch (Exception e) {
            log.error("[AiJob:{}] [Topic] Thất bại — {}", jobId, e.getMessage(), e);
            failJob(jobId, buildUserFriendlyError(e));
        }

        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void failJob(String jobId, String errorMessage) {
        try {
            AiJob job = aiJobRepository.findById(jobId).orElse(null);
            if (job != null) {
                job.setStatus("FAILED");
                job.setErrorMessage(errorMessage);
                aiJobRepository.save(job);
            }
        } catch (Exception saveEx) {
            log.error("[AiJob:{}] Không thể lưu trạng thái FAILED: {}", jobId, saveEx.getMessage());
        }
    }

    private String buildUserFriendlyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")) {
            return "Tất cả AI keys đã hết quota. Vui lòng thử lại sau 1 phút.";
        }
        if (msg.contains("503") || msg.contains("UNAVAILABLE") || msg.contains("Connection refused")) {
            return "Dịch vụ AI tạm thời không khả dụng. Vui lòng kiểm tra LiteLLM Proxy đang chạy.";
        }
        if (msg.contains("400") || msg.contains("INVALID_ARGUMENT")) {
            return "Lỗi 400: Tài liệu không hợp lệ hoặc quá ngắn để sinh câu hỏi.";
        }
        return "Lỗi xử lý AI: " + msg;
    }
}
