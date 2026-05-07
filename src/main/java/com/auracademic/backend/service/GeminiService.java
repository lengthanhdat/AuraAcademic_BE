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

    // gemini-2.5-flash: model chính. parseResponse() đã xử lý đúng thinking parts.
    // Khi hết quota hoặc lỗi → AiProcessingService sẽ fallback sang Groq tự động.
    private static final String PRIMARY_MODEL   = "gemini-2.5-flash";
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
    // PUBLIC API — Tự động kiểm duyệt tài liệu giảng dạy
    // ─────────────────────────────────────────────────────────────────

    /**
     * Gọi Gemini AI để kiểm duyệt tài liệu — đa ngôn ngữ, nhận diện mọi từ cấm/tục tĩu.
     * Trả về Map:
     *   - "approved"        (Boolean)      : true = duyệt, false = từ chối
     *   - "reason"          (String)       : lý do cụ thể bằng tiếng Việt
     *   - "violationType"   (String)       : PROFANITY | SEXUAL_CONTENT | VIOLENCE |
     *                                        HATE_SPEECH | POLITICAL | COPYRIGHT | NONE
     *   - "suggestedTags"   (List<String>) : gợi ý thẻ tag học thuật
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reviewMaterial(String title, String description,
                                               String subject, String fileType,
                                               String category, String fileName,
                                               String extractedContent) {
        // Chuẩn bị phần nội dung file để đưa vào prompt
        String contentSection = (extractedContent != null && !extractedContent.isBlank())
            ? "\n\n# NỘI DUNG BÊN TRONG FILE (trích xuất tự động — đây là phần QUAN TRỌNG NHẤT):\n```\n" + extractedContent + "\n```"
            : "\n\n# NỘI DUNG FILE: Không thể trích xuất (file nhị phân/video) — chỉ kiểm tra metadata.";

        String prompt = """
            # VAI TRÒ
            Bạn là hệ thống kiểm duyệt nội dung tự động (Content Moderation AI) của nền tảng giáo dục Aura Academic.
            Nhiệm vụ: BẢO VỆ môi trường học thuật bằng cách phát hiện và chặn mọi nội dung không phù hợp
            TRƯỚC KHI tài liệu được công khai đến học sinh.

            # THÔNG TIN TÀI LIỆU
            - Tên file gốc : %s
            - Tiêu đề     : %s
            - Mô tả       : %s
            - Môn học     : %s
            - Định dạng   : %s
            - Phân loại   : %s
            %s

            # TIÊU CHÍ KIỂM DUYỆT (phân tích TẤT CẢ ngôn ngữ — bao gồm cả nội dung file bên trên)

            ## 🔴 MỨC 1 — TỰ ĐỘNG TỪ CHỐI NGAY

            ### 1. NGÔN TỤC & TỪ CẤM (PROFANITY) — nhận diện ĐA NGÔN NGỮ:
            **Tiếng Việt:** đụ, lồn, cặc, địt, đéo, đm, đmm, vcl, vkl, clm, dcm, địt con mẹ, đụ má
            Teen code / biến thể: đ**, c*c, l*n, đ.ụ, d.ụ, loz, cak, buoi, buồi
            Leet speak: d_u, c4c, l0n, du ma, du me, du ba, d1t, c4c
            Tiếng lóng: vãi, vl, đù má, tổ cha, đĩ, điếm, cave

            **Tiếng Anh:** fuck, shit, bitch, cunt, cock, dick, pussy, ass, asshole, motherfucker, whore, slut
            Leet: f*ck, sh!t, b!tch, @ss, f**k, fck, btch, cnt

            **Tiếng Trung/Nhật/Hàn:** 操你妈, 草泥马, 滚蛋, 傻逼, ちくしょう, くそ, 씨발, 개새끼, 존나

            **Tiếng Pháp/TBN:** merde, putain, salope, coño, puta, joder, hijo de puta

            **Ký tự thay thế:** *, @, !, 0, 3, 4, $ thay chữ → VẪN VI PHẠM nếu đọc ra được từ tục.

            ### 2. NỘI DUNG TÌNH DỤC (SEXUAL_CONTENT): Mô tả hành vi tình dục, khiêu dâm, NSFW
            ### 3. BẠO LỰC & KỲ THỊ (VIOLENCE / HATE_SPEECH): Kêu gọi bạo lực, phân biệt chủng tộc
            ### 4. CHÍNH TRỊ NHẠY CẢM (POLITICAL): Tuyên truyền, chống nhà nước
            ### 5. VI PHẠM BẢN QUYỀN (COPYRIGHT): Sao chép tài liệu thương mại rõ ràng

            ## 🟢 NGOẠI LỆ — KHÔNG TỪ CHỐI:
            - Từ y khoa (dương vật, âm đạo, sinh sản) trong văn cảnh học thuật → CHẤP NHẬN
            - Phân tích văn học nhạy cảm với mô tả học thuật → CHẤP NHẬN
            - Thông tin sơ sài không rõ ràng → ưu tiên DUYỆT

            ## 🟡 MỨC 2 — TÍNH HỌC THUẬT:
            - Nội dung phải liên quan đến giáo dục, học tập, môn học khai báo
            - Tiêu đề và mô tả phải rõ ràng, chuyên nghiệp

            # FORMAT TRẢ VỀ (DUY NHẤT JSON, KHÔNG GIẢI THÍCH THÊM):
            {"approved":true,"reason":"Lý do tiếng Việt","violationType":"NONE","suggestedTags":["tag1","tag2"]}

            violationType chỉ nhận: PROFANITY, SEXUAL_CONTENT, VIOLENCE, HATE_SPEECH, POLITICAL, COPYRIGHT, NONE
            """.formatted(
                fileName        != null ? fileName        : "",
                title           != null ? title           : "",
                description     != null ? description     : "",
                subject         != null ? subject         : "",
                fileType        != null ? fileType        : "",
                category        != null ? category        : "",
                contentSection
        );

        try {
            String raw = callGeminiText(prompt);
            String sanitized = raw.trim();
            if (sanitized.contains("```")) {
                int s = sanitized.indexOf("```"), e = sanitized.lastIndexOf("```");
                if (s != e) { sanitized = sanitized.substring(s + 3, e); }
                if (sanitized.toLowerCase().startsWith("json")) sanitized = sanitized.substring(4).trim();
            }
            Map<String, Object> result = mapper.readValue(sanitized, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            log.info("[Gemini-Review] approved={}, violationType={}, reason={}",
                    result.get("approved"), result.get("violationType"), result.get("reason"));
            return result;
        } catch (Exception ex) {
            log.error("[Gemini-Review] Kiểm duyệt thất bại: {}", ex.getMessage());
            return Map.of(
                "approved", true,
                "reason", "AI kiểm duyệt tạm thời không khả dụng. Tài liệu được chuyển chờ Admin duyệt thủ công.",
                "violationType", "NONE",
                "suggestedTags", List.of()
            );
        }
    }

    /** Gọi Gemini API và trả về raw text (không parse Question) */
    private String callGeminiText(String prompt) throws Exception {
        String url = buildUrl(PRIMARY_MODEL);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        requestBody.put("generationConfig", Map.of(
            "temperature", 0.1,
            "response_mime_type", "application/json"
        ));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String responseStr = restTemplate.postForObject(url, new HttpEntity<>(requestBody, headers), String.class);

        Map<String, Object> responseMap = mapper.readValue(responseStr, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new RuntimeException("Gemini candidates rỗng");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        return (String) parts.get(0).get("text");
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
        Map<String, Object> responseMap = mapper.readValue(rawResponse, new TypeReference<>() {});
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            // Kiểm tra promptFeedback để cung cấp thông báo lỗi rõ hơn
            Object feedback = responseMap.get("promptFeedback");
            log.error("[Gemini] candidates rỗng. promptFeedback: {}", feedback);
            throw new RuntimeException("Gemini không trả về câu trả lời (candidates rỗng). PromptFeedback: " + feedback);
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            Object finishReason = candidates.get(0).get("finishReason");
            throw new RuntimeException("Gemini trả về content null. finishReason: " + finishReason);
        }
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");

        // Tìm part chứa text output thực sự (bỏ qua các 'thought' parts của thinking model)
        // Thinking model (gemini-2.5-*) có thể có: [{"thought": true, "text": "..."}, {"text": "JSON"}]
        String rawText = null;
        for (Map<String, Object> part : parts) {
            Boolean isThought = (Boolean) part.get("thought");
            if (Boolean.TRUE.equals(isThought)) continue; // Bỏ qua thought part
            rawText = (String) part.get("text");
            if (rawText != null && !rawText.isBlank()) break;
        }
        if (rawText == null || rawText.isBlank()) {
            throw new RuntimeException("Gemini không trả về text output (tất cả parts đều là thought hoặc rỗng).");
        }

        // Làm sạch markdown fence nếu có
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

        // Trích xuất JSON array (bỏ qua text thừa trước/sau)
        int arrayStart = sanitized.indexOf('[');
        int arrayEnd   = sanitized.lastIndexOf(']');
        if (arrayStart != -1 && arrayEnd != -1 && arrayEnd > arrayStart) {
            sanitized = sanitized.substring(arrayStart, arrayEnd + 1);
        }

        log.info("[Gemini] JSON từ AI (500 ký tự đầu): {}",
            sanitized.length() > 500 ? sanitized.substring(0, 500) + "..." : sanitized);

        // Thử sửa lỗi JSON nếu bị cắt ngang
        String repairedJson = repairTruncatedJson(sanitized);

        // Parse JSON array thành List<Question>
        List<Map<String, Object>> rawList;
        try {
            rawList = mapper.readValue(repairedJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[Gemini] Lỗi parse JSON sau khi repair. Nội dung: {}", repairedJson);
            throw new RuntimeException("Gemini trả về định dạng không hợp lệ: " + e.getMessage());
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
