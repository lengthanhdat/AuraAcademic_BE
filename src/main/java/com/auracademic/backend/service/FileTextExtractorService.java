package com.auracademic.backend.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;


import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * Trích xuất nội dung văn bản từ các file tài liệu được mã hóa base64.
 * Hỗ trợ: DOCX, PDF, PPTX
 * Giới hạn: lấy tối đa MAX_CHARS ký tự đầu để gửi cho AI kiểm duyệt.
 */
@Service
public class FileTextExtractorService {

    private static final Logger log = LoggerFactory.getLogger(FileTextExtractorService.class);

    /** Số ký tự tối đa trích xuất để gửi cho AI (tránh quá dài → tốn token) */
    private static final int MAX_CHARS = 4000;

    /**
     * Trích xuất text từ data URI base64 — GIỚI HẠN 4000 ký tự để gửi cho Gemini AI.
     * @param fileUrl  data URI (data:application/...;base64,xxxx)
     * @param fileType "pdf", "docx", "pptx"
     */
    public String extractText(String fileUrl, String fileType) {
        String full = extractFullText(fileUrl, fileType);
        if (full.length() > MAX_CHARS) {
            return full.substring(0, MAX_CHARS) + "\n...[nội dung bị cắt bớt để kiểm duyệt]";
        }
        return full;
    }

    /**
     * Trích xuất TOÀN BỘ văn bản (không giới hạn) — dùng cho ProfanityFilterService
     * để scan TOÀN BỘ file, kể cả khi nội dung vi phạm nằm ở trang cuối.
     */
    public String extractFullText(String fileUrl, String fileType) {
        if (fileUrl == null || !fileUrl.startsWith("data:")) {
            return ""; // URL bên ngoài hoặc video → không extract
        }
        try {
            String base64Part = fileUrl.substring(fileUrl.indexOf(',') + 1);
            byte[] bytes = Base64.getDecoder().decode(base64Part);

            String text = switch (fileType == null ? "" : fileType.toLowerCase()) {
                case "docx", "doc" -> extractDocx(bytes);
                case "pdf"         -> extractPdf(bytes);
                case "pptx", "ppt" -> extractPptx(bytes);
                default            -> "";
            };

            log.info("[TextExtractor] Trích xuất thành công {} ký tự từ file '{}'", text.length(), fileType);
            if (!text.isBlank()) {
                String preview = text.substring(0, Math.min(text.length(), 100)).replace("\n", " ");
                log.info("[TextExtractor] Bản xem trước nội dung: '{}...'", preview);
            }
            return text;

        } catch (Exception e) {
            log.error("[TextExtractor] LỖI trích xuất nội dung từ file " + fileType, e);
            return "";
        }
    }



    // ── DOCX ──────────────────────────────────────────────────────────────────

    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            });
            doc.getTables().forEach(table -> {
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> {
                        String text = cell.getText();
                        if (text != null && !text.isBlank()) {
                            sb.append(text).append(' ');
                        }
                    });
                    sb.append('\n');
                });
            });
            return sb.toString().strip();
        }
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Sắp xếp theo vị trí tọa độ giúp đọc chuẩn dòng cột
            return stripper.getText(doc).strip();
        }
    }

    // ── PPTX ──────────────────────────────────────────────────────────────────

    private String extractPptx(byte[] bytes) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            ppt.getSlides().forEach(slide -> { // Quét toàn bộ slide không giới hạn
                slide.getShapes().forEach(shape -> {
                    try {
                        if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                            String text = ts.getText();
                            if (text != null && !text.isBlank()) {
                                sb.append(text).append('\n');
                            }
                        } else if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTable table) {
                            table.getRows().forEach(row -> {
                                row.getCells().forEach(cell -> {
                                    String text = cell.getText();
                                    if (text != null && !text.isBlank()) {
                                        sb.append(text).append(' ');
                                    }
                                });
                                sb.append('\n');
                            });
                        }
                    } catch (Exception ignored) {}
                });
            });
            return sb.toString().strip();
        }
    }
}
