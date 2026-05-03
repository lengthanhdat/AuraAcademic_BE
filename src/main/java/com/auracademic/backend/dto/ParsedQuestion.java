package com.auracademic.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO dai dien mot cau hoi da duoc trich xuat tu file DOCX/PDF.
 * Khac voi Question model (luu trong exam), ParsedQuestion dung de transfer
 * ve frontend truoc khi giao vien xem truoc va chon lua.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedQuestion {
    private String id;                  // "q1", "q2", ...
    private String text;                // Noi dung cau hoi
    private String imageBase64;         // Anh nhung trong cau hoi (data:image/png;base64,...)
    private List<ParsedOption> options; // Cac lua chon A B C D

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedOption {
        private String id;          // "a", "b", "c", "d"
        private String label;       // "A", "B", "C", "D"
        private String text;        // Noi dung lua chon
        private boolean isCorrect;  // True neu duoc danh dau la dap an dung
    }
}
