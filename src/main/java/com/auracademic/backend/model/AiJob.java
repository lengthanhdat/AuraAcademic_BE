package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.List;

/**
 * Lưu trạng thái của mỗi job xử lý AI trong MongoDB (collection: ai_jobs).
 * Vòng đời: PROCESSING → DONE | FAILED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_jobs")
public class AiJob {

    @Id
    private String id;

    /** Trạng thái: "PROCESSING" | "DONE" | "FAILED" */
    private String status;

    /** Danh sách câu hỏi AI tạo ra — chỉ có giá trị khi status = DONE */
    private List<Question> questions;

    /** Văn bản đã trích xuất từ tài liệu — dùng cho chat-refine sau này */
    private String extractedText;

    /** Danh sách ảnh dạng Base64 được trích xuất từ DOCX/PDF */
    private List<String> extractedImages;

    /** Thông báo lỗi — chỉ có giá trị khi status = FAILED */
    private String errorMessage;

    /** Thời điểm tạo job (ms) — dùng để TTL index sau này nếu cần dọn dẹp */
    @Indexed
    private long createdAt;
}
