package com.auracademic.backend.service;

import com.auracademic.backend.model.Option;
import com.auracademic.backend.model.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Dịch vụ AI dự phòng sử dụng Groq API (OpenAI-compatible).
 *
 * Được kích hoạt khi GeminiService gặp lỗi 429 (Rate Limit) hoặc
 * RESOURCE_EXHAUSTED (hết quota miễn phí).
 *
 * Groq API tương thích chuẩn OpenAI — sử dụng định dạng Chat Completions.
 * Tất cả prompt và định dạng JSON output được giữ nguyên như GeminiService
 * để đảm bảo kết quả nhất quán.
 */
@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    @Value("${groq.api.key:}")
    private String apiKey;

    @Autowired
    private SettingService settingService;

    private String getActiveApiKey() {
        return settingService.getSetting("groq.api.key", this.apiKey);
    }

    @Value("${groq.url}")
    private String apiUrl;

    @Value("${groq.model}")
    private String modelName;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public GroqService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);   // 15 giây kết nối
        factory.setReadTimeout(120_000);     // 2 phút đọc response
        this.restTemplate = new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API — được gọi từ AiProcessingService khi Gemini thất bại
    // ─────────────────────────────────────────────────────────────────

    /**
     * Sinh câu hỏi từ văn bản (text-only, không hỗ trợ ảnh vì Groq free tier).
     * Được gọi như fallback khi Gemini báo lỗi 429/RESOURCE_EXHAUSTED.
     */
    public List<Question> generateQuestions(String documentText, int questionCount) throws Exception {
        log.info("[Groq] Bắt đầu sinh {} câu hỏi (văn bản dài {} ký tự)", questionCount, documentText.length());
        String prompt = buildGeneratePrompt(documentText, questionCount);
        return callGroqApi(prompt);
    }

    // ─────────────────────────────────────────────────────────────────
    // PROMPT BUILDER — giữ nguyên quy tắc như GeminiService
    // ─────────────────────────────────────────────────────────────────

    private String buildGeneratePrompt(String text, int count) {
        return """
            TÀI LIỆU:
            ---
            %s
            ---

            NHIỆM VỤ:
            Bạn là một chuyên gia số hóa đề thi. Hãy trích xuất ĐÚNG %d câu hỏi trắc nghiệm từ nội dung trên sang định dạng JSON. Không thừa, không thiếu.

            QUY TẮC BẮT BUỘC:
            1. Trích xuất nội dung câu hỏi và 4 phương án y như trong tài liệu gốc.
            2. QUAN TRỌNG: Phải tách BIỆT phần phương án (A, B, C, D) ra khỏi nội dung câu hỏi. Trường `text` của câu hỏi TUYỆT ĐỐI KHÔNG ĐƯỢC chứa các chữ A. B. C. D. hay nội dung của các phương án.
            3. ĐÁNH DẤU ĐÁP ÁN ĐÚNG: Mỗi câu hỏi phải có đủ 4 lựa chọn. Đánh dấu `isCorrect: true` cho đáp án đúng. Nếu không có gợi ý, mặc định chọn đáp án đầu tiên (A) là đúng.
            4. QUY TẮC BẮT BUỘC VỀ TOÁN HỌC: BẮT BUỘC sử dụng định dạng LaTeX (bọc trong `$ ... $` hoặc `$$ ... $$`) cho TẤT CẢ các công thức toán học, biến số, ký hiệu, phương trình trong cả nội dung câu hỏi lẫn các đáp án.
            5. Trả về DUY NHẤT một JSON array. Không giải thích gì thêm. Không dùng markdown (không có ```json).
            6. Nếu tài liệu thực tế không có đủ %d câu hỏi, hãy trích xuất toàn bộ câu hỏi có trong đó, sau đó tự tạo thêm câu hỏi bám sát nội dung tài liệu để đủ đúng %d câu.

            Định dạng JSON:
            [{"id":"1","type":"Trắc nghiệm","text":"Nội dung câu hỏi","options":[{"id":"a","text":"Nội dung A","isCorrect":true},{"id":"b","text":"Nội dung B","isCorrect":false},{"id":"c","text":"Nội dung C","isCorrect":false},{"id":"d","text":"Nội dung D","isCorrect":false}]}]
            """.formatted(text, count, count, count);
    }

    public Map<String, Object> checkHealth() {
        String key = getActiveApiKey();
        if (key == null || key.isBlank()) return Map.of("ok", false, "msg", "Chưa có token");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(key);
            // Gõ thử model list của groq
            restTemplate.exchange("https://api.groq.com/openai/v1/models", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return Map.of("ok", true, "model", modelName, "rpm", "30 RPM", "tpm", "6,000 TPM", "rpd", "14,400 RPD");
        } catch (HttpStatusCodeException e) {
            return Map.of("ok", false, "msg", "Token không hợp lệ: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return Map.of("ok", false, "msg", "Lỗi kết nối: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // GROQ HTTP CALL (OpenAI Chat Completions format)
    // ─────────────────────────────────────────────────────────────────

    private List<Question> callGroqApi(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getActiveApiKey());

        Map<String, Object> message = Map.of(
            "role", "user",
            "content", prompt
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("messages", List.of(message));
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 8192);

        try {
            String responseStr = restTemplate.postForObject(
                apiUrl, new HttpEntity<>(requestBody, headers), String.class
            );
            return parseGroqResponse(responseStr);
        } catch (HttpStatusCodeException e) {
            log.warn("[Groq] HTTP {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Groq API lỗi " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PARSE RESPONSE — Groq trả về OpenAI Chat Completions format
    // ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Question> parseGroqResponse(String rawResponse) throws Exception {
        Map<String, Object> responseMap = mapper.readValue(rawResponse, new TypeReference<>() {});

        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("[Groq] Không trả về câu trả lời (choices rỗng).");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String rawText = (String) message.get("content");

        // Làm sạch nếu Groq vẫn bọc trong markdown fence
        String sanitized = rawText.trim();
        if (sanitized.contains("```")) {
            int first = sanitized.indexOf("```");
            int last  = sanitized.lastIndexOf("```");
            if (first != last) {
                String inner = sanitized.substring(first + 3, last);
                if (inner.toLowerCase().startsWith("json")) inner = inner.substring(4);
                sanitized = inner.trim();
            }
        }

        // Tìm và cắt lấy phần JSON array nếu có text thừa trước/sau
        int arrayStart = sanitized.indexOf('[');
        int arrayEnd   = sanitized.lastIndexOf(']');
        if (arrayStart != -1 && arrayEnd != -1 && arrayEnd > arrayStart) {
            sanitized = sanitized.substring(arrayStart, arrayEnd + 1);
        }

        log.info("[Groq] JSON từ AI: {}", sanitized.length() > 500 ? sanitized.substring(0, 500) + "..." : sanitized);

        // Parse JSON array thành List<Question>
        List<Map<String, Object>> rawList;
        try {
            rawList = mapper.readValue(sanitized, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[Groq] Lỗi parse JSON. Nội dung: {}", sanitized);
            throw new RuntimeException("Groq AI trả về định dạng không hợp lệ. Vui lòng thử lại.");
        }

        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> q : rawList) {
            Question question = new Question();
            question.setId(String.valueOf(q.get("id")));
            question.setType(q.getOrDefault("type", "Trắc nghiệm").toString());
            question.setText(String.valueOf(q.get("text")));

            List<Map<String, Object>> rawOptions = mapper.convertValue(q.get("options"), new TypeReference<>() {});
            List<Option> options = new ArrayList<>();
            for (Map<String, Object> o : rawOptions) {
                Option opt = new Option();
                opt.setId(String.valueOf(o.get("id")));
                opt.setText(String.valueOf(o.get("text")));
                opt.setCorrect(Boolean.parseBoolean(String.valueOf(o.get("isCorrect"))));
                options.add(opt);
            }
            question.setOptions(options);
            questions.add(question);
        }

        log.info("[Groq] Parse thành công {} câu hỏi", questions.size());
        return questions;
    }
}
