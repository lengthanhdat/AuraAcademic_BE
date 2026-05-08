package com.auracademic.backend.service;

import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.model.Material;
import com.auracademic.backend.repository.MaterialRepository;
import com.auracademic.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final GeminiService geminiService;
    private final FileTextExtractorService fileTextExtractorService;
    private final ProfanityFilterService profanityFilterService;

    // 50MB limit per file
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private static final List<String> ALLOWED_MIME_PREFIXES = Arrays.asList(
            "data:application/pdf;",
            "data:application/vnd.ms-powerpoint;",
            "data:application/vnd.openxmlformats-officedocument.presentationml.presentation;",
            "data:application/msword;",
            "data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;",
            "data:video/",
            "data:image/",
            "http://", "https://");

    public MaterialService(MaterialRepository materialRepository,
            UserRepository userRepository,
            AuditLogService auditLogService,
            GeminiService geminiService,
            FileTextExtractorService fileTextExtractorService,
            ProfanityFilterService profanityFilterService) {
        this.materialRepository = materialRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.geminiService = geminiService;
        this.fileTextExtractorService = fileTextExtractorService;
        this.profanityFilterService = profanityFilterService;
    }

    /** Validate file data URI and size */
    private void validateFile(String fileUrl, long sizeBytes, String fileName) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new AuthException("Nội dung tài liệu không được trống.");
        }
        if (sizeBytes > MAX_FILE_SIZE) {
            throw new AuthException("File vượt quá giới hạn tối đa 50MB.");
        }
        boolean allowed = ALLOWED_MIME_PREFIXES.stream().anyMatch(fileUrl::startsWith);
        if (!allowed) {
            throw new AuthException("Định dạng file không được hỗ trợ.");
        }
    }

    /**
     * Upload material:
     * - Admin → lưu thẳng vào DB với status "published"
     * - Teacher → AI kiểm duyệt ĐỒNG BỘ trước khi lưu:
     * - AI duyệt → lưu với status "published", tag được bổ sung
     * - AI từ chối → ném lỗi ngay, KHÔNG lưu vào DB
     * - AI bị lỗi → lưu với status "pending_review" để Admin duyệt thủ công
     */
    @SuppressWarnings("unchecked")
    public Material upload(String userId, String role, Map<String, Object> req, String ip) {
        String fileUrl = (String) req.get("fileUrl");
        long sizeBytes = req.get("fileSizeBytes") != null ? ((Number) req.get("fileSizeBytes")).longValue() : 0L;
        String fileName = (String) req.getOrDefault("fileName", "");

        validateFile(fileUrl, sizeBytes, fileName);

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng."));

        String title = (String) req.getOrDefault("title", "");
        String description = (String) req.getOrDefault("description", "");
        String subject = (String) req.getOrDefault("subject", "");
        String category = (String) req.getOrDefault("category", "reference");
        String fileType = (String) req.getOrDefault("fileType", "pdf");
        List<String> tags = (List<String>) req.getOrDefault("tags", List.of());

        // ── AI gate: chỉ áp dụng cho giảng viên ──────────────────────────────
        String initialStatus = "teacher".equals(role) ? "pending_review" : "published";
        List<String> finalTags = new java.util.ArrayList<>(tags);

        if ("teacher".equals(role)) {
            // ── Lớp 1: Pre-filter cứng (Java) — scan TOÀN BỘ văn bản, không giới hạn
            // ───────
            String fullText = fileTextExtractorService.extractFullText(fileUrl, fileType);
            log.info("[Pre-Filter] Trích xuất {} ký tự (toàn bộ) từ file '{}'", fullText.length(), fileName);

            if (!fullText.isBlank()) {
                var filterResult = profanityFilterService.check(fullText);
                if (!filterResult.passed()) {
                    String blocked = filterResult.matchedWord();
                    log.warn("[Pre-Filter] Phát hiện từ cấm '{}' trong '{}' — từ chối ngay.", blocked, fileName);
                    auditLogService.log(userId, user.getEmail(), "MATERIAL_BLOCKED_PROFANITY", ip,
                            null, false,
                            "BLOCKED[PROFANITY-PREFILTER]: " + title + " — Từ cấm: '" + blocked + "'");
                    throw new AuthException(
                            "Tài liệu bị từ chối: Phát hiện từ ngữ không phù hợp. " +
                                    "Vui lòng kiểm tra lại toàn bộ nội dung tài liệu.");
                }

                log.info("[Pre-Filter] Văn bản sạch, tiếp tục gửi Gemini AI kiểm duyệt.");
            }

            // ── Lới 2: Gemini AI — chỉ dùng 4000 ký tự đầu cho phân tích ngữ cảnh ────
            String extractedContent = fullText.length() > 4000
                    ? fullText.substring(0, 4000) + "\n...[cắt bật]"
                    : fullText;

            log.info("[AI-Gate] Kiểm duyệt tài liệu trước khi lưu: '{}'", title);
            try {
                Map<String, Object> aiResult = geminiService.reviewMaterial(
                        title, description, subject, fileType, category, fileName, extractedContent);

                boolean approved = Boolean.TRUE.equals(aiResult.get("approved"));
                String reason = (String) aiResult.getOrDefault("reason", "");
                String violationType = (String) aiResult.getOrDefault("violationType", "NONE");

                if (!approved) {
                    // AI từ chối → ném lỗi ngay, file KHÔNG được lưu
                    log.warn("[AI-Gate] Từ chối tài liệu '{}' | vi phạm: {} | lý do: {}", title, violationType, reason);
                    auditLogService.log(userId, user.getEmail(), "MATERIAL_AI_BLOCKED", ip,
                            null, false,
                            "BLOCKED[" + violationType + "]: " + title + " — " + reason);
                    throw new AuthException("Tài liệu bị từ chối bởi AI kiểm duyệt: " + reason);
                }

                // AI duyệt → publish ngay, gộp tags gợi ý
                initialStatus = "published";
                List<String> suggestedTags = (List<String>) aiResult.getOrDefault("suggestedTags", List.of());
                if (!suggestedTags.isEmpty()) {
                    java.util.Set<String> merged = new java.util.LinkedHashSet<>(tags);
                    merged.addAll(suggestedTags);
                    finalTags = new java.util.ArrayList<>(merged);
                }
                log.info("[AI-Gate] Duyệt tài liệu '{}': {}", title, reason);

            } catch (AuthException e) {
                throw e; // Re-throw rejection, không nuốt lỗi này
            } catch (Exception e) {
                // AI bị lỗi mạng/timeout → fallback an toàn: lưu pending_review để Admin duyệt
                log.error("[AI-Gate] AI không khả dụng, fallback pending_review: {}", e.getMessage());
                initialStatus = "pending_review";
            }
        }

        // ── Lưu vào DB chỉ khi đã qua AI gate ───────────────────────────────
        Material m = new Material();
        m.setTitle(title);
        m.setDescription(description);
        m.setSubject(subject);
        m.setCategory(category);
        m.setFileType(fileType);
        m.setFileUrl(fileUrl);
        m.setFileName(fileName);
        m.setFileSizeBytes(sizeBytes);
        m.setUploadedBy(userId);
        m.setUploaderName(user.getFullName());
        m.setUploaderRole(role);
        m.setTags(finalTags);
        m.setStatus(initialStatus);
        m.setDownloadCount(0);
        m.setCreatedAt(LocalDateTime.now());
        m.setUpdatedAt(LocalDateTime.now());

        materialRepository.save(m);
        auditLogService.log(userId, user.getEmail(), "MATERIAL_UPLOAD", ip,
                null, true, "Uploaded[" + initialStatus + "]: " + title + " [" + role + "]");
        return m;
    }

    /** Update metadata (title, desc, tags, subject, category) */
    @SuppressWarnings("unchecked")
    public Material update(String materialId, String userId, String role, Map<String, Object> req, String ip) {
        Material m = materialRepository.findById(materialId)
                .orElseThrow(() -> new AuthException("Tài liệu không tồn tại."));

        if ("teacher".equals(role) && !m.getUploadedBy().equals(userId)) {
            throw new AuthException("Bạn không có quyền chỉnh sửa tài liệu này.");
        }

        if (req.containsKey("title"))
            m.setTitle((String) req.get("title"));
        if (req.containsKey("description"))
            m.setDescription((String) req.get("description"));
        if (req.containsKey("subject"))
            m.setSubject((String) req.get("subject"));
        if (req.containsKey("category"))
            m.setCategory((String) req.get("category"));
        if (req.containsKey("tags"))
            m.setTags((List<String>) req.get("tags"));
        m.setUpdatedAt(LocalDateTime.now());
        // Reset to pending_review when teacher edits
        if ("teacher".equals(role))
            m.setStatus("pending_review");

        materialRepository.save(m);
        var user = userRepository.findById(userId).orElse(null);
        auditLogService.log(userId, user != null ? user.getEmail() : "", "MATERIAL_UPDATE", ip,
                null, true, "Updated: " + m.getTitle());
        return m;
    }

    /** Admin: approve or reject */
    public Material review(String materialId, String adminId, String action, String reason, String ip) {
        Material m = materialRepository.findById(materialId)
                .orElseThrow(() -> new AuthException("Tài liệu không tồn tại."));

        if ("approve".equals(action)) {
            m.setStatus("published");
            m.setRejectionReason(null);
        } else if ("reject".equals(action)) {
            m.setStatus("rejected");
            m.setRejectionReason(reason);
        } else {
            throw new AuthException("Hành động không hợp lệ (approve/reject).");
        }
        m.setUpdatedAt(LocalDateTime.now());
        materialRepository.save(m);

        var admin = userRepository.findById(adminId).orElse(null);
        auditLogService.log(adminId, admin != null ? admin.getEmail() : "", "MATERIAL_REVIEW", ip,
                null, true, action.toUpperCase() + ": " + m.getTitle());
        return m;
    }

    /** Delete */
    public void delete(String materialId, String userId, String role, String ip) {
        Material m = materialRepository.findById(materialId)
                .orElseThrow(() -> new AuthException("Tài liệu không tồn tại."));
        if ("teacher".equals(role) && !m.getUploadedBy().equals(userId)) {
            throw new AuthException("Bạn không có quyền xóa tài liệu này.");
        }
        materialRepository.delete(m);
        var user = userRepository.findById(userId).orElse(null);
        auditLogService.log(userId, user != null ? user.getEmail() : "", "MATERIAL_DELETE", ip,
                null, true, "Deleted: " + m.getTitle());
    }

    /** Increment download counter */
    public void incrementDownload(String materialId) {
        materialRepository.findById(materialId).ifPresent(m -> {
            m.setDownloadCount(m.getDownloadCount() + 1);
            materialRepository.save(m);
        });
    }

    // ── Queries ─────────────────────────────────────────────────────────────────

    public List<Material> getMyMaterials(String userId) {
        return materialRepository.findByUploadedByOrderByCreatedAtDesc(userId);
    }

    public List<Material> getPendingReview() {
        return materialRepository.findByStatusOrderByCreatedAtDesc("pending_review");
    }

    public List<Material> getAllForAdmin() {
        return materialRepository.findAll();
    }

    public List<Material> getPublished(String keyword, String subject) {
        if (keyword != null && !keyword.isBlank()) {
            return materialRepository.searchPublished(keyword);
        }
        if (subject != null && !subject.isBlank() && !"all".equalsIgnoreCase(subject)) {
            return materialRepository.findByStatusAndSubjectIgnoreCaseOrderByCreatedAtDesc("published", subject);
        }
        return materialRepository.findByStatusOrderByCreatedAtDesc("published");
    }
}
