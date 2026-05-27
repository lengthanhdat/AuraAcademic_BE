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
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Service giao tiếp với LiteLLM Proxy (http://localhost:4000).
 *
 * LiteLLM tự động xử lý:
 *   - Load Balancing: chia đều request qua nhiều API Keys (Gemini, Groq...)
 *   - Fallback: khi Gemini hết quota → tự chuyển sang Groq
 *   - Retry: thử lại tối đa 3 lần mỗi request
 *
 * Service này dùng OpenAI Chat Completions format (chuẩn hóa bởi LiteLLM).
 * GeminiService và GroqService cũ được giữ lại như "Emergency Bypass" phòng khi LiteLLM tắt.
 */
@Service
public class LiteLlmService {

    private static final Logger log = LoggerFactory.getLogger(LiteLlmService.class);

    @Value("${litellm.proxy.url:http://localhost:4000}")
    private String proxyUrl;

    @Value("${litellm.proxy.key:sk-litellm-aura-academic-2026}")
    private String masterKey;

    @Value("${litellm.model.main:main-ai}")
    private String mainModel;

    @Value("${litellm.model.vision:vision-ai}")
    private String visionModel;

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiteLlmService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 10 giây kết nối
        factory.setReadTimeout(180_000);     // 3 phút đọc (vì LiteLLM cần thời gian retry)
        this.restTemplate = new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API — Thay thế GeminiService + GroqService
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sinh câu hỏi từ văn bản tài liệu (text-only).
     * LiteLLM sẽ tự cân bằng tải qua các Gemini Keys và fallback sang Groq nếu cần.
     */
    public List<Question> generateQuestions(String documentText, int questionCount) throws Exception {
        log.info("[LiteLLM] Sinh {} câu hỏi từ văn bản ({} ký tự)", questionCount, documentText.length());
        String prompt = buildGeneratePrompt(documentText, questionCount);
        return callLiteLlm(prompt, mainModel);
    }

    /**
     * Sinh câu hỏi từ văn bản + hình ảnh (PDF có ảnh).
     * Gọi Vision model — LiteLLM map Base64 sang Gemini Vision format tự động.
     */
    public List<Question> generateQuestionsWithImages(String documentText, List<String> images, int questionCount) throws Exception {
        log.info("[LiteLLM] Sinh {} câu hỏi từ PDF có {} hình ảnh", questionCount, images.size());
        String prompt = buildGeneratePrompt(documentText, questionCount)
            + "\nQUY TẮC BỔ SUNG: BẮT BUỘC CHUYỂN TẤT CẢ CÁC CÔNG THỨC TOÁN HỌC TRONG ẢNH SANG ĐỊNH DẠNG LATEX (bọc trong `$ ... $` hoặc `$$ ... $$`).";
        return callLiteLlmWithImages(prompt, images, visionModel);
    }

    /**
     * Sinh câu hỏi từ chủ đề tự do (không cần tài liệu).
     */
    public List<Question> generateByTopic(String topic, String difficulty, String language, int count) throws Exception {
        log.info("[LiteLLM] Sinh {} câu từ chủ đề '{}' (độ khó: {})", count, topic, difficulty);
        String prompt = buildTopicPrompt(topic, difficulty, language, count);
        return callLiteLlm(prompt, mainModel);
    }

    /**
     * Tinh chỉnh bộ câu hỏi theo lệnh chat của giáo viên.
     */
    public List<Question> refineQuestions(String command, String documentText, List<String> images) throws Exception {
        log.info("[LiteLLM] Chat-refine câu hỏi — lệnh: '{}'", command);
        String sanitizedText = documentText != null ? documentText : "";
        String prompt = buildRefinePrompt(command, sanitizedText);
        if (images != null && !images.isEmpty()) {
            return callLiteLlmWithImages(prompt, images, visionModel);
        }
        return callLiteLlm(prompt, mainModel);
    }

    /**
     * Gọi AI để tự chọn đáp án đúng từ danh sách options.
     */
    public String chooseCorrectAnswer(String questionText, List<Map<String, Object>> options) throws Exception {
        log.info("[LiteLLM] Auto-choose đáp án đúng cho câu hỏi: '{}'", questionText.substring(0, Math.min(50, questionText.length())));
        String prompt = buildChooseAnswerPrompt(questionText, options);
        return callLiteLlmRaw(prompt, mainModel);
    }

    /**
     * Sinh phản hồi chat tự do (dùng cho explain, content moderation...).
     */
    public String generateChatResponse(String prompt) throws Exception {
        log.info("[LiteLLM] Sinh phản hồi chat...");
        return callLiteLlmRaw(prompt, mainModel);
    }

    /**
     * Kiểm duyệt tài liệu (chuyển từ GeminiService).
     */
    public Map<String, Object> reviewMaterial(String title, String description, String subject,
                                               String fileType, String category, String fileName,
                                               String extractedContent) {
        String contentSection = (extractedContent != null && !extractedContent.isBlank())
            ? "\n\n# NỘI DUNG BÊN TRONG FILE:\n```\n" + extractedContent + "\n```"
            : "\n\n# NỘI DUNG FILE: Không thể trích xuất.";

        String prompt = """
            # VAI TRÒ
            Bạn là hệ thống kiểm duyệt nội dung tự động của nền tảng giáo dục Aura Academic.

            # THÔNG TIN TÀI LIỆU
            - Tên file: %s
            - Tiêu đề: %s
            - Mô tả: %s
            - Môn học: %s
            - Định dạng: %s
            - Phân loại: %s
            %s

            # TIÊU CHÍ KIỂM DUYỆT
            Từ chối ngay nếu có: ngôn tục (mọi ngôn ngữ), nội dung tình dục, bạo lực, kỳ thị, chính trị nhạy cảm, vi phạm bản quyền.
            Chấp nhận: từ y khoa học thuật, phân tích văn học, nội dung giáo dục bình thường.

            # FORMAT TRẢ VỀ (JSON ONLY):
            {"approved":true,"reason":"Lý do tiếng Việt","violationType":"NONE","suggestedTags":["tag1","tag2"]}

            violationType chỉ nhận: PROFANITY, SEXUAL_CONTENT, VIOLENCE, HATE_SPEECH, POLITICAL, COPYRIGHT, NONE
            """.formatted(fileName, title, description, subject, fileType, category, contentSection);

        try {
            String raw = callLiteLlmRaw(prompt, mainModel);
            String sanitized = raw.trim();
            if (sanitized.contains("```")) {
                int s = sanitized.indexOf("```"), e = sanitized.lastIndexOf("```");
                if (s != e) { sanitized = sanitized.substring(s + 3, e); }
                if (sanitized.toLowerCase().startsWith("json")) sanitized = sanitized.substring(4).trim();
            }
            Map<String, Object> result = mapper.readValue(sanitized, new TypeReference<>() {});
            log.info("[LiteLLM-Review] approved={}, violationType={}", result.get("approved"), result.get("violationType"));
            return result;
        } catch (Exception ex) {
            log.error("[LiteLLM-Review] Kiểm duyệt thất bại: {}", ex.getMessage());
            return Map.of("approved", true, "reason", "AI tạm thời không khả dụng.",
                          "violationType", "NONE", "suggestedTags", List.of());
        }
    }

    /**
     * Kiểm tra LiteLLM Proxy có đang chạy không.
     */
    public Map<String, Object> checkHealth() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(masterKey);
            String healthUrl = proxyUrl + "/health";
            restTemplate.exchange(healthUrl, org.springframework.http.HttpMethod.GET,
                                  new HttpEntity<>(headers), String.class);
            return Map.of("ok", true, "proxy", proxyUrl, "msg", "LiteLLM Proxy đang hoạt động");
        } catch (Exception e) {
            return Map.of("ok", false, "proxy", proxyUrl, "msg", "Không kết nối được tới LiteLLM: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL HTTP CALLS
    // ─────────────────────────────────────────────────────────────────────────

    /** Gọi LiteLLM với text-only prompt → parse ra List<Question> */
    private List<Question> callLiteLlm(String prompt, String model) throws Exception {
        String raw = callLiteLlmRaw(prompt, model);
        return parseJsonResponse(raw, model);
    }

    /** Gọi LiteLLM với prompt + hình ảnh Base64 → parse ra List<Question> */
    private List<Question> callLiteLlmWithImages(String prompt, List<String> images, String model) throws Exception {
        String raw = callLiteLlmRawWithImages(prompt, images, model);
        return parseJsonResponse(raw, model);
    }

    /** Gọi LiteLLM và trả về raw text (không parse Question) */
    private String callLiteLlmRaw(String prompt, String model) throws Exception {
        String endpoint = proxyUrl + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(masterKey);

        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(message));
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 8192);

        try {
            String responseStr = restTemplate.postForObject(endpoint, new HttpEntity<>(requestBody, headers), String.class);
            return extractContentFromOpenAiResponse(responseStr, model);
        } catch (HttpStatusCodeException e) {
            log.warn("[LiteLLM] HTTP {} từ model '{}' — {}", e.getStatusCode(), model, e.getResponseBodyAsString());
            throw new RuntimeException("LiteLLM lỗi " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    /** Gọi LiteLLM với hình ảnh (OpenAI vision format — LiteLLM tự chuyển sang Gemini/Claude Vision) */
    @SuppressWarnings("unchecked")
    private String callLiteLlmRawWithImages(String prompt, List<String> images, String model) throws Exception {
        String endpoint = proxyUrl + "/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(masterKey);

        // OpenAI Vision format: content là array gồm text + image_url parts
        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", prompt));
        for (String base64Image : images) {
            contentParts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:image/jpeg;base64," + base64Image)
            ));
        }

        Map<String, Object> message = Map.of("role", "user", "content", contentParts);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(message));
        requestBody.put("temperature", 0.2);
        requestBody.put("max_tokens", 8192);

        try {
            String responseStr = restTemplate.postForObject(endpoint, new HttpEntity<>(requestBody, headers), String.class);
            return extractContentFromOpenAiResponse(responseStr, model);
        } catch (HttpStatusCodeException e) {
            log.warn("[LiteLLM] HTTP {} từ vision model '{}' — {}", e.getStatusCode(), model, e.getResponseBodyAsString());
            throw new RuntimeException("LiteLLM Vision lỗi " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    /** Trích xuất text từ OpenAI Chat Completions response format */
    @SuppressWarnings("unchecked")
    private String extractContentFromOpenAiResponse(String responseStr, String model) throws Exception {
        Map<String, Object> responseMap = mapper.readValue(responseStr, new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("[LiteLLM] Không có choices trong response từ model '" + model + "'");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new RuntimeException("[LiteLLM] Content rỗng từ model '" + model + "'");
        }
        return content.trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARSE JSON RESPONSE → List<Question>
    // ─────────────────────────────────────────────────────────────────────────

    private List<Question> parseJsonResponse(String rawText, String model) throws Exception {
        String sanitized = rawText.trim();

        // Làm sạch markdown fence nếu có
        if (sanitized.contains("```")) {
            int first = sanitized.indexOf("```");
            int last = sanitized.lastIndexOf("```");
            if (first != last) {
                String inner = sanitized.substring(first + 3, last);
                if (inner.toLowerCase().startsWith("json")) inner = inner.substring(4);
                sanitized = inner.trim();
            }
        }

        // Trích xuất JSON array (bỏ qua text thừa trước/sau)
        int arrayStart = sanitized.indexOf('[');
        int arrayEnd = sanitized.lastIndexOf(']');
        if (arrayStart != -1 && arrayEnd != -1 && arrayEnd > arrayStart) {
            sanitized = sanitized.substring(arrayStart, arrayEnd + 1);
        }

        log.info("[LiteLLM] JSON từ '{}' (500 ký tự đầu): {}", model,
                 sanitized.length() > 500 ? sanitized.substring(0, 500) + "..." : sanitized);

        // Sửa JSON bị cắt ngang
        sanitized = repairTruncatedJson(sanitized);

        List<Map<String, Object>> rawList;
        try {
            rawList = mapper.readValue(sanitized, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("[LiteLLM] Parse JSON thất bại. Nội dung: {}", sanitized);
            throw new RuntimeException("AI trả về định dạng không hợp lệ: " + e.getMessage());
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

        log.info("[LiteLLM] Parse thành công {} câu hỏi từ '{}'", questions.size(), model);
        return questions;
    }

    private String repairTruncatedJson(String json) {
        try {
            mapper.readTree(json);
            return json; // Hợp lệ rồi
        } catch (Exception e) {
            log.warn("[LiteLLM] JSON bị cắt ngang, đang tự sửa...");
            int lastComma = json.lastIndexOf(",{\"id\"");
            if (lastComma == -1) lastComma = json.lastIndexOf(", {\"id\"");
            if (lastComma > 0) return json.substring(0, lastComma) + "]";
            if (!json.endsWith("]")) return json + "]";
            return json;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMPT BUILDERS (Giữ nguyên prompt chất lượng cao từ GeminiService)
    // ─────────────────────────────────────────────────────────────────────────

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
            2. QUAN TRỌNG: Phải tách BIỆT phần phương án (A, B, C, D) ra khỏi nội dung câu hỏi. Trường `text` TUYỆT ĐỐI KHÔNG ĐƯỢC chứa các chữ A. B. C. D. hay nội dung phương án.
            3. ĐÁNH DẤU ĐÁP ÁN ĐÚNG: Đánh dấu `isCorrect: true` cho đáp án đúng dựa vào gợi ý. Nếu không có, mặc định A là đúng.
            4. Giữ nguyên các tag ảnh [IMG_0], [IMG_1]... và bảng biểu Markdown ở đúng vị trí.
            5. BẮT BUỘC dùng LaTeX (bọc trong `$ ... $` hoặc `$$ ... $$`) cho TẤT CẢ công thức toán học.
            6. Trả về DUY NHẤT một JSON array. Không giải thích. Không dùng markdown fence (không có ```json).
            7. Nếu không đủ %d câu, tự tạo thêm câu bám sát nội dung để đủ đúng %d câu.

            Định dạng JSON:
            [{"id":"1","type":"Trắc nghiệm","text":"Nội dung câu hỏi","options":[{"id":"a","text":"Nội dung A","isCorrect":true},{"id":"b","text":"Nội dung B","isCorrect":false},{"id":"c","text":"Nội dung C","isCorrect":false},{"id":"d","text":"Nội dung D","isCorrect":false}]}]
            """.formatted(text, count, count, count);
    }

    private String buildTopicPrompt(String topic, String difficulty, String language, int count) {
        String difficultyDesc = switch (difficulty.toUpperCase()) {
            case "EASY"   -> "Mức cơ bản (Nhận biết & Thông hiểu). Câu hỏi trực tiếp, rõ ràng.";
            case "HARD"   -> "Mức nâng cao (Vận dụng cao). Câu hỏi đòi hỏi tư duy sâu.";
            case "EXPERT" -> "Mức chuyên gia (Phân hóa cao). Câu hỏi học thuật, bẫy tinh vi.";
            default       -> "Mức trung bình (Thông hiểu & Vận dụng). Hỗn hợp dễ và khó vừa.";
        };
        String langInstruction = "en".equalsIgnoreCase(language)
            ? "IMPORTANT: Write ALL questions and answers in English."
            : "QUAN TRỌNG: Viết TẤT CẢ câu hỏi và đáp án bằng Tiếng Việt.";

        return """
            # VAI TRÒ
            Bạn là Giáo sư chuyên gia biên soạn đề thi hàng đầu Việt Nam. Tự biên soạn bộ câu hỏi dựa trên kho tri thức nội tại.

            # YÊU CẦU
            %s

            # THÔNG SỐ
            - Số câu: ĐÚNG %d câu
            - Độ khó: %s
            - %s

            # TIÊU CHUẨN
            1. Chính xác 100%% học thuật.
            2. Distractor (đáp án sai) phải hợp lý, dễ nhầm.
            3. 4 lựa chọn/câu, chỉ 1 đúng.
            4. KHÔNG dùng dạng "Tất cả đều đúng".
            5. BẮT BUỘC dùng LaTeX cho công thức toán học.

            # OUTPUT (JSON ARRAY ONLY — KHÔNG GIẢI THÍCH):
            [{"id":"1","type":"Trắc nghiệm","text":"...","options":[{"id":"a","text":"...","isCorrect":true},{"id":"b","text":"...","isCorrect":false},{"id":"c","text":"...","isCorrect":false},{"id":"d","text":"...","isCorrect":false}]}]
            """.formatted(topic, count, difficultyDesc, langInstruction);
    }

    private String buildRefinePrompt(String command, String text) {
        return """
            Bạn là trợ lý giáo dục. Thực hiện yêu cầu bên dưới dựa trên tài liệu.
            YÊU CẦU: %s

            QUY TẮC:
            1. CHỈ trả về JSON array hợp lệ. Bắt đầu bằng [ và kết thúc bằng ].
            2. TUYỆT ĐỐI KHÔNG dùng markdown fence (không có ```json).
            3. Mỗi câu có đúng 4 lựa chọn, chỉ 1 đúng.
            4. Giữ nguyên tag ảnh [IMG_0], [IMG_1]... và bảng Markdown.

            TÀI LIỆU:
            %s
            """.formatted(command, text);
    }

    private String buildChooseAnswerPrompt(String question, List<Map<String, Object>> options) {
        StringBuilder optionLines = new StringBuilder();
        for (Map<String, Object> option : options) {
            String label = String.valueOf(option.getOrDefault("label", "")).trim().toUpperCase();
            String text = String.valueOf(option.getOrDefault("text", "")).trim();
            if (!label.isBlank() && !text.isBlank()) {
                optionLines.append(label).append(". ").append(text).append("\n");
            }
        }
        return "You are an expert teacher. Choose exactly one correct answer.\n"
            + "Return only valid JSON, no markdown: {\"correctLabel\":\"A\"}\n"
            + "Question: " + question + "\n" + optionLines;
    }
}
