package com.auracademic.backend.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DTO đại diện một câu hỏi đã được trích xuất từ file DOCX/PDF.
 * Khác với Question model (lưu trong exam), ParsedQuestion dùng để transfer
 * về frontend trước khi giáo viên xem trước và chọn lựa.
 */
public class ParsedQuestion {
    private String id;                  // "q1", "q2", ...
    private String text;                // Nội dung câu hỏi
    private String imageBase64;         // Ảnh nhúng trong câu hỏi (data:image/png;base64,...)
    private List<ParsedOption> options; // Các lựa chọn A B C D

                public static class ParsedOption {
        private String id;          // "a", "b", "c", "d"
        private String label;       // "A", "B", "C", "D"
        private String text;        // Nội dung lựa chọn
        @JsonProperty("isCorrect")
        private boolean isCorrect;  // True nếu được đánh dấu là đáp án đúng

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public boolean isCorrect() { return isCorrect; }
        public void setCorrect(boolean isCorrect) { this.isCorrect = isCorrect; }

    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public List<ParsedOption> getOptions() {
        return options;
    }

    public void setOptions(List<ParsedOption> options) {
        this.options = options;
    }
}
