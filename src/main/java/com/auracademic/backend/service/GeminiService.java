package com.auracademic.backend.service;

import com.auracademic.backend.model.Option;
import com.auracademic.backend.model.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Service gọi Gemini API để sinh câu hỏi trắc nghiệm.
 *
 * Chiến lược Retry (xử lý 429 / 503):
 *   - Tối đa 3 lần thử (maxAttempts = 3)
 *   - Exponential backoff: 2s → 4s → 8s
 *   - Nếu cả 3 lần đều thất bại → @Recover ném RuntimeException rõ ràng
 *
 * LƯU Ý: @Retryable yêu cầu Spring AOP proxy — KHÔNG được gọi method này
 * từ bên trong cùng class (self-invocation). Hãy gọi qua AiProcessingService.
 */
@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    // Dùng gemini-1.5-flash làm primary: nhanh hơn, ổn định hơn 2.5-flash ở free tier
    private static final String PRIMARY_MODEL   = "gemini-2.5-flash";
    private static final String FALLBACK_MODEL  = "gemini-2.5-flash";
    private static final String GEMINI_HOST     = "https://generativelanguage.googleapis.com/";

    // Không giới hạn văn bản theo yêu cầu của user

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);   // 15 giây kết nối
        factory.setReadTimeout(120_000);     // 2 phút đọc response
        this.restTemplate = new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API — được gọi từ AiProcessingService
    // ─────────────────────────────────────────────────────────────────

    /**
     * Sinh câu hỏi từ văn bản (text-only, không gửi ảnh).
     *
     * @Retryable: Spring tự động retry khi có exception.
     *   - retryFor: bắt mọi exception (bao gồm 429, 503 từ RestTemplate)
     *   - maxAttempts = 3: thử lần 1, nếu lỗi → chờ 2s → lần 2 → chờ 4s → lần 3
     *   - multiplier = 2.0: backoff tăng gấp đôi mỗi lần (2s, 4s, 8s)
     */
    @Retryable(
        retryFor  = { Exception.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public List<Question> generateQuestions(String documentText, int questionCount) throws Exception {
        log.info("[Gemini] Bắt đầu sinh {} câu hỏi (văn bản dài {} ký tự)", questionCount, documentText.length());
        if (documentText.length() > 500) {
            log.info("[Gemini] 500 ký tự đầu tiên: {}", documentText.substring(0, 500).replace("\n", " "));
        }
        String sanitizedText = documentText != null ? documentText : "";
        String prompt = buildGeneratePrompt(sanitizedText, questionCount);
        return callGeminiApi(prompt, PRIMARY_MODEL);
    }

    /**
     * Sinh câu hỏi từ văn bản và ảnh (dành cho PDF).
     */
    @Retryable(
        retryFor  = { Exception.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public List<Question> generateQuestionsWithImages(String documentText, List<String> images, int questionCount) throws Exception {
        log.info("[Gemini] Bắt đầu sinh {} câu hỏi từ PDF có {} ảnh", questionCount, images.size());
        String sanitizedText = documentText != null ? documentText : "";
        String prompt = buildGeneratePrompt(sanitizedText, questionCount) + "\nQUY TẮC BỔ SUNG: BẮT BUỘC CHUYỂN TẤT CẢ CÁC CÔNG THỨC TOÁN HỌC TRONG ẢNH SANG ĐỊNH DẠNG LATEX (bọc trong `$ ... $` hoặc `$$ ... $$`).";
        return callGeminiApiWithImages(prompt, images, PRIMARY_MODEL);
    }


    /**
     * @Recover được gọi khi TẤT CẢ các lần retry đều thất bại.
     * Phải có cùng kiểu trả về và tham số Exception đầu tiên.
     */
    @Recover
    public List<Question> recoverGenerateQuestions(Exception ex, String documentText, int questionCount) {
        log.error("[Gemini] Đã thử 3 lần nhưng vẫn thất bại. Lỗi cuối: {}", ex.getMessage());
        throw new RuntimeException(
            "AI không phản hồi sau 3 lần thử. Lý do: " + ex.getMessage(), ex
        );
    }

    @Recover
    public List<Question> recoverGenerateQuestionsWithImages(Exception ex, String documentText, List<String> images, int questionCount) {
        log.error("[Gemini] Đã thử 3 lần nhưng vẫn thất bại (kèm ảnh). Lỗi cuối: {}", ex.getMessage());
        throw new RuntimeException(
            "AI không phản hồi sau 3 lần thử. Lý do: " + ex.getMessage(), ex
        );
    }

    /**
     * Tinh chỉnh câu hỏi theo lệnh của giáo viên (dùng cho chat-refine panel).
     * Giữ nguyên multimodal để hỗ trợ ảnh nếu cần.
     */
    @Retryable(
        retryFor  = { Exception.class },
        maxAttempts = 3,
        backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public List<Question> refineQuestions(String command, String documentText, List<String> images) throws Exception {
        String sanitizedText = documentText != null ? documentText : "";
        String prompt  = buildRefinePrompt(command, sanitizedText);
        return callGeminiApiWithImages(prompt, images, PRIMARY_MODEL);
    }

    @Recover
    public List<Question> recoverRefineQuestions(Exception ex, String command, String documentText, List<String> images) {
        log.error("[Gemini] Chat-refine thất bại sau 3 lần retry: {}", ex.getMessage());
        throw new RuntimeException("Trợ lý AI không phản hồi. Vui lòng thử lại sau.", ex);
    }

    // ─────────────────────────────────────────────────────────────────
    // PROMPT BUILDERS
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
            3. ĐÁNH DẤU ĐÁP ÁN ĐÚNG: Mỗi câu hỏi phải có đủ 4 lựa chọn. Đánh dấu `isCorrect: true` cho đáp án đúng dựa vào gợi ý trong đề (ví dụ: chữ in đậm, gạch chân, hoặc đáp án có màu khác). Nếu không có gợi ý, mặc định chọn đáp án đầu tiên (A) là đúng.
            4. NẾU trong nội dung gốc có các đoạn mã [IMG_0], [IMG_1]... thì BẮT BUỘC PHẢI GIỮ LẠI nguyên vẹn các đoạn mã này ở đúng vị trí của chúng.
            5. NẾU trong nội dung gốc có Bảng biểu (được thể hiện bằng Markdown), BẮT BUỘC PHẢI GIỮ LẠI toàn bộ cấu trúc Markdown của bảng đó.
            6. Trả về DUY NHẤT một JSON array. Không giải thích gì thêm.
            7. Nếu tài liệu thực tế không có đủ %d câu hỏi, hãy trích xuất toàn bộ câu hỏi có trong đó, sau đó tự tạo thêm câu hỏi bám sát nội dung tài liệu để đủ đúng %d câu.

            Định dạng JSON:
            [{"id":"1","type":"Trắc nghiệm","text":"Nội dung câu hỏi (Không chứa đáp án)","options":[{"id":"a","text":"Nội dung A","isCorrect":true},{"id":"b","text":"Nội dung B","isCorrect":false},{"id":"c","text":"Nội dung C","isCorrect":false},{"id":"d","text":"Nội dung D","isCorrect":false}]}]
            """.formatted(text, count, count, count);
    }

    private String buildRefinePrompt(String command, String text) {
        return """
            Bạn là trợ lý giáo dục. Thực hiện yêu cầu bên dưới dựa trên tài liệu.
            YÊU CẦU: %s

            QUY TẮC BẮT BUỘC:
            1. CHỈ trả về một JSON array hợp lệ. Bắt đầu bằng [ và kết thúc bằng ].
            2. TUYỆT ĐỐI KHÔNG dùng markdown (không có ```json, không có ```).
            3. Mỗi câu có đúng 4 lựa chọn, chỉ 1 lựa chọn đúng.
            4. Không thêm bất kỳ văn bản nào ngoài JSON array.
            5. Nếu trích xuất câu hỏi từ tài liệu, phải giữ NGUYÊN VĂN, không được tự ý sửa đổi.
            6. Giữ nguyên các tag ảnh [IMG_0], [IMG_1]... và bảng biểu Markdown ở đúng vị trí.

            TÀI LIỆU:
            %s
            """.formatted(command, text);
    }

    // ─────────────────────────────────────────────────────────────────
    // GEMINI HTTP CALLS
    // ─────────────────────────────────────────────────────────────────

    /** Gọi Gemini API (chỉ text, không ảnh) */
    private List<Question> callGeminiApi(String prompt, String modelName) throws Exception {
        String url = buildUrl(modelName);
        List<Map<String, Object>> parts = List.of(Map.of("text", prompt));
        return callGemini(url, parts);
    }

    /** Gọi Gemini API có kèm ảnh (multimodal) */
    private List<Question> callGeminiApiWithImages(String prompt, List<String> images, String modelName) throws Exception {
        String url = buildUrl(modelName);
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        if (images != null) {
            for (String base64 : images) {
                parts.add(Map.of("inline_data", Map.of("mime_type", "image/jpeg", "data", base64)));
            }
        }
        return callGemini(url, parts);
    }

    private List<Question> callGemini(String url, List<Map<String, Object>> parts) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(Map.of("parts", parts)));
        requestBody.put("generationConfig", Map.of(
            "temperature", 0.2,
            "response_mime_type", "application/json"
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String responseStr = restTemplate.postForObject(
                url, new HttpEntity<>(requestBody, headers), String.class
            );
            return parseResponse(responseStr);
        } catch (HttpStatusCodeException e) {
            log.warn("[Gemini] HTTP {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Gemini API lỗi " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    private String buildUrl(String modelName) {
        // Luôn dùng v1beta để hỗ trợ response_mime_type
        String apiVersion = "v1beta";
        return GEMINI_HOST + apiVersion + "/models/" + modelName + ":generateContent?key=" + apiKey;
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Parse JSON response từ Gemini.
     * Xử lý cả 2 trường hợp:
     *   1. Gemini trả về thuần JSON (nhờ responseMimeType: application/json)
     *   2. Gemini vẫn bọc trong ```json ... ``` (fallback)
     */
    @SuppressWarnings("unchecked")
    private List<Question> parseResponse(String rawResponse) throws Exception {
        // Lấy text từ Gemini response structure
        Map<String, Object> responseMap = mapper.readValue(rawResponse, new TypeReference<>() {});
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Gemini không trả về câu trả lời (candidates rỗng).");
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        String rawText = (String) parts.get(0).get("text");

        // Làm sạch nếu vẫn còn markdown fence
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
        log.info("[Gemini] JSON từ AI: {}", sanitized);

        // Thử sửa lỗi JSON nếu bị cắt ngang (do giới hạn max_output_tokens)
        String repairedJson = repairTruncatedJson(sanitized);

        // Parse JSON array thành List<Question>
        List<Map<String, Object>> rawList;
        try {
            rawList = mapper.readValue(repairedJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[Gemini] Lỗi parse JSON sau khi repair. Nội dung: {}", repairedJson);
            throw new RuntimeException("AI trả về định dạng không hợp lệ. Vui lòng thử lại.");
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

        log.info("[Gemini] Parse thành công {} câu hỏi", questions.size());
        return questions;
    }
    
    /**
     * Sửa lỗi JSON bị cắt ngang bằng cách tìm object hoàn chỉnh cuối cùng.
     */
    private String repairTruncatedJson(String json) {
        try {
            mapper.readTree(json);
            return json; // Nếu hợp lệ thì trả về luôn
        } catch (Exception e) {
            log.warn("[Gemini] JSON bị cắt ngang (có thể do quá dài). Đang tự động sửa...");
            // Tìm object cuối cùng bằng pattern ", {"id"" hoặc ",{"id""
            int lastComma = json.lastIndexOf(",{\"id\"");
            if (lastComma == -1) {
                lastComma = json.lastIndexOf(", {\"id\"");
            }
            if (lastComma > 0) {
                return json.substring(0, lastComma) + "]";
            }
            
            // Nếu chỉ có 1 câu và bị cắt
            if (!json.endsWith("]")) {
                return json + "]}"; // Thử heuristic đóng ngoặc đại khái
            }
            return json;
        }
    }
}
