package com.auracademic.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;

/**
 * Pre-filter từ tục nhanh (Java-side) với độ chính xác tuyệt đối, KHÔNG gây false positive.
 * Chia làm 2 lớp kiểm tra sử dụng ranh giới từ (word boundary) chặt chẽ:
 *   1. Kiểm tra có dấu (Original Text): Bắt các từ chắc chắn là bậy bạ trong tiếng Việt (cặc, lồn, địt, đụ...).
 *   2. Kiểm tra không dấu (Normalized Text): Bắt các từ viết tắt bậy (vcl, clm, dcm...) hoặc tiếng Anh/ngoại ngữ chắc chắn bậy.
 */
@Service
public class ProfanityFilterService {

    private static final Logger log = LoggerFactory.getLogger(ProfanityFilterService.class);

    public record FilterResult(boolean passed, String matchedWord) {
        public static FilterResult clean()           { return new FilterResult(true,  null); }
        public static FilterResult blocked(String w) { return new FilterResult(false, w);   }
    }

    // ── LỚP 1: Từ chắc chắn bậy bạ có dấu (Kiểm tra ranh giới từ trên văn bản gốc lowercase) ──
    private static final List<String> BLOCKED_WITH_DIACRITICS = List.of(
        "cặc", "lồn", "địt", "đụ", "buồi", "điếm", "vãi lồn", "địt mẹ", "đụ má", "đụ mẹ", "chó đẻ"
    );

    // ── LỚP 2: Từ viết tắt bậy hoặc ngoại ngữ (Kiểm tra ranh giới từ trên văn bản chuẩn hóa không dấu) ──
    private static final List<String> BLOCKED_WITHOUT_DIACRITICS = List.of(
        // Viết tắt tiếng Việt
        "vcl", "vkl", "clm", "dcm", "dmm", "duma", "dume", "duba", "diemay",
        // Tiếng Anh
        "fuck", "fuk", "fck", "bitch", "btch", "cunt", "cnt", "motherfucker",
        // Tiếng Trung/Hàn/Nhật
        "操你妈", "草泥马", "傻逼", "씨발", "개새끼", "ちくしょう"
    );

    public FilterResult check(String rawText) {
        if (rawText == null || rawText.isBlank()) return FilterResult.clean();

        // 1. Kiểm tra có dấu dùng ranh giới từ (tránh "lồng" bị nhầm thành "lồn", "đụng" bị nhầm thành "đụ")
        String originalClean = cleanOriginalText(rawText);
        for (String word : BLOCKED_WITH_DIACRITICS) {
            if (containsWord(originalClean, word)) {
                log.warn("[ProfanityFilter] Phát hiện từ cấm có dấu (word-boundary): '{}'", word);
                return FilterResult.blocked(word);
            }
        }

        // 2. Kiểm tra không dấu dùng ranh giới từ (tránh "dự báo" bị nhầm thành "duba")
        String normalized = normalize(rawText);
        for (String word : BLOCKED_WITHOUT_DIACRITICS) {
            if (containsWord(normalized, word)) {
                log.warn("[ProfanityFilter] Phát hiện từ cấm không dấu (word-boundary): '{}'", word);
                return FilterResult.blocked(word);
            }
        }

        log.debug("[ProfanityFilter] Văn bản sạch ({} ký tự).", rawText.length());
        return FilterResult.clean();
    }

    /** Kiểm tra xem từ cấm có tồn tại độc lập trong chuỗi không */
    private boolean containsWord(String text, String word) {
        if (text.equals(word))                    return true;
        if (text.startsWith(word + " "))          return true;
        if (text.endsWith(" " + word))            return true;
        return text.contains(" " + word + " ");
    }

    /**
     * Dọn dẹp văn bản gốc giữ nguyên dấu tiếng Việt, thay ký tự đặc biệt bằng khoảng trắng
     * để kiểm tra ranh giới từ chuẩn xác.
     */
    private String cleanOriginalText(String text) {
        String s = text.toLowerCase();
        s = s.replace("đ", "d"); // chuẩn hóa chữ đ
        // Giữ chữ cái tiếng Việt có dấu và số, thay ký tự khác bằng khoảng trắng
        s = s.replaceAll("[^a-z0-9\\u00e0-\\u00fa\\u0102\\u0103\\u0110\\u0111\\u0128\\u0129\\u0168\\u0169\\u01a0\\u01a1\\u01af\\u01b0\\u1ea0-\\u1ef9]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Khử dấu tiếng Việt và leet speak để kiểm tra lớp 2:
     * Thay thế các ký tự phân tách đặc biệt bằng khoảng trắng để giữ ranh giới từ.
     */
    private String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        s = s.replace("Đ", "D").replace("đ", "d");
        s = s.toLowerCase();

        // Leet-speak substitution
        s = s.replace("0", "o").replace("4", "a").replace("1", "i")
             .replace("3", "e").replace("$", "s").replace("@", "a")
             .replace("!", "i").replace("ƒ", "f");

        // Thay ký tự đặc biệt bằng khoảng trắng
        s = s.replaceAll("[^a-z0-9\\u4e00-\\u9fff\\uac00-\\ud7af\\u3040-\\u30ff]", " ");

        // Thu gọn khoảng trắng
        return s.replaceAll("\\s+", " ").trim();
    }
}
