package com.auracademic.backend.service;

import com.auracademic.backend.model.AiJob;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.repository.AiJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrator bất đồng bộ cho pipeline xử lý AI:
 *   1. Trích xuất văn bản từ tài liệu (DocumentExtractorService)
 *   2. Gọi Gemini AI để sinh câu hỏi (GeminiService — có @Retryable)
 *   3. Cập nhật trạng thái job trong MongoDB
 *
 * Tách @Async ở đây thay vì trong GeminiService để đảm bảo
 * @Retryable của GeminiService vẫn hoạt động qua Spring AOP proxy.
 *
 * QUAN TRỌNG: Spring chỉ inject @Async qua proxy — KHÔNG gọi method này
 * từ bên trong class (self-invocation sẽ bypass proxy).
 */
@Service
public class AiProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingService.class);

    @Autowired
    private AiJobRepository aiJobRepository;

    @Autowired
    private DocumentExtractorService documentExtractorService;

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private GroqService groqService;

    /**
     * Xử lý toàn bộ pipeline AI trong một thread riêng biệt.
     *
     * @param jobId  ID của AiJob đã được tạo trước với status=PROCESSING
     * @param file   File tài liệu giáo viên upload (DOCX, PDF, TXT)
     * @param count  Số câu hỏi cần tạo
     * @return CompletableFuture<Void> — caller không cần chờ kết quả
     */
    @Async
    public CompletableFuture<Void> processJob(String jobId, byte[] fileBytes, String filename, int count) {
        log.info("[AiJob:{}] Bắt đầu xử lý — file: {}, số câu: {}",
            jobId, filename, count);

        try {
            // ── Bước 1: Trích xuất văn bản từ tài liệu ────────────────
            log.info("[AiJob:{}] Đang trích xuất văn bản...", jobId);
            Map<String, Object> content = documentExtractorService.extractContent(fileBytes, filename);
            String extractedText = (String) content.get("text");
            @SuppressWarnings("unchecked")
            List<String> extractedImages = (List<String>) content.getOrDefault("images", List.of());

            if ((extractedText == null || extractedText.trim().length() < 10) && extractedImages.isEmpty()) {
                failJob(jobId, "Nội dung tài liệu quá ngắn hoặc không đọc được.");
                return CompletableFuture.completedFuture(null);
            }

            log.info("[AiJob:{}] Trích xuất xong — {} ký tự văn bản, {} hình ảnh", jobId, extractedText.length(), extractedImages.size());

            // ── Bước 2: Thử goi Gemini AI (Primary) ──────────────────
            List<Question> questions = null;
            String usedProvider = "Gemini";
            try {
                log.info("[AiJob:{}] Đang gọi Gemini AI...", jobId);
                if (!extractedImages.isEmpty()) {
                    questions = geminiService.generateQuestionsWithImages(extractedText, extractedImages, count);
                } else {
                    questions = geminiService.generateQuestions(extractedText, count);
                }
            } catch (Exception geminiEx) {
                log.warn("[AiJob:{}] Gemini thất bại: {}", jobId, geminiEx.getMessage());

                // Nếu lỗi do hết quota (429 / RESOURCE_EXHAUSTED / API key bị khóa 403)
                // → Tự động chuyển sang Groq
                if (isQuotaError(geminiEx)) {
                    log.warn("[AiJob:{}] Gemini hết quota — Đang chuyển sang Groq dự phòng...", jobId);
                    usedProvider = "Groq";
                    questions = groqService.generateQuestions(extractedText, count);
                    log.info("[AiJob:{}] Groq sinh thành công {} câu hỏi", jobId, questions.size());
                } else {
                    // Lỗi khác (network, format...) → ném lại để xử lý bình thường
                    throw geminiEx;
                }
            }

            // ── Bước 3: Lưu kết quả → DONE ──────────────────────
            AiJob job = aiJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy job: " + jobId));

            job.setStatus("DONE");
            job.setQuestions(questions);
            job.setExtractedText(extractedText);
            job.setExtractedImages(extractedImages);
            aiJobRepository.save(job);

            log.info("[AiJob:{}] Hoàn thành (provider: {}) — {} câu hỏi đã được tạo", jobId, usedProvider, questions.size());

        } catch (Exception e) {
            log.error("[AiJob:{}] Thất bại — {}", jobId, e.getMessage(), e);
            failJob(jobId, buildUserFriendlyError(e));
        }

        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

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

    /**
     * Kiểm tra xem exception có phải do hết quota / API key bị khóa không.
     * Bao gồm: 429 Rate Limit, RESOURCE_EXHAUSTED, và 403 với thông báo lộ key của Google.
     */
    private boolean isQuotaError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("429")
            || msg.contains("resource_exhausted")
            || msg.contains("ratequota")
            || msg.contains("quota")
            || (msg.contains("403") && msg.contains("permission_denied"))
            || msg.contains("reported as leaked");
    }

    /** Chuyển exception thành thông báo lỗi thân thiện với người dùng */
    private String buildUserFriendlyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")) {
            return "Lỗi 429: " + msg;
        }
        if (msg.contains("503") || msg.contains("UNAVAILABLE")) {
            return "Dịch vụ AI tạm thời không khả dụng (503). Vui lòng thử lại sau.";
        }
        if (msg.contains("400") || msg.contains("INVALID_ARGUMENT")) {
            return "Lỗi 400: " + msg;
        }
        if (msg.contains("candidates rỗng")) {
            return "AI không tạo được câu hỏi từ tài liệu này. Hãy kiểm tra nội dung tài liệu.";
        }
        return "Lỗi xử lý AI: " + msg;
    }
}
