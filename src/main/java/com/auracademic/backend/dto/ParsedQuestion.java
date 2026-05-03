package com.auracademic.backend.dto;


import java.util.List;

/**
 * DTO dai dien mot cau hoi da duoc trich xuat tu file DOCX/PDF.
 * Khac voi Question model (luu trong exam), ParsedQuestion dung de transfer
 * ve frontend truoc khi giao vien xem truoc va chon lua.
 */
public class ParsedQuestion {
    private String id;                  // "q1", "q2", ...
    private String text;                // Noi dung cau hoi
    private String imageBase64;         // Anh nhung trong cau hoi (data:image/png;base64,...)
    private List<ParsedOption> options; // Cac lua chon A B C D

                public static class ParsedOption {
        private String id;          // "a", "b", "c", "d"
        private String label;       // "A", "B", "C", "D"
        private String text;        // Noi dung lua chon
        private boolean isCorrect;  // True neu duoc danh dau la dap an dung

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
