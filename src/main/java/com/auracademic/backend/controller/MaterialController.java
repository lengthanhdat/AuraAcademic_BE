package com.auracademic.backend.controller;

import com.auracademic.backend.model.Material;
import com.auracademic.backend.security.UserPrincipal;
import com.auracademic.backend.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    // ── Public: fetch published materials ────────────────────────────────────────

    /** GET /api/materials/published?keyword=&subject= */
    @GetMapping("/published")
    public ResponseEntity<List<Material>> getPublished(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String subject) {
        return ResponseEntity.ok(materialService.getPublished(keyword, subject));
    }

    // ── Teacher: manage own materials ────────────────────────────────────────────

    /** GET /api/materials/my - Teacher sees their own uploads */
    @GetMapping("/my")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<List<Material>> getMine(@AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(materialService.getMyMaterials(p.getId()));
    }

    /** POST /api/materials/upload - Teacher or Admin uploads */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Material> upload(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody Map<String, Object> req,
            HttpServletRequest httpReq) {
        String ip = getIp(httpReq);
        return ResponseEntity.ok(materialService.upload(p.getId(), p.getRole(), req, ip));
    }

    /** PUT /api/materials/{id} - Update metadata */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Material> update(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody Map<String, Object> req,
            HttpServletRequest httpReq) {
        return ResponseEntity.ok(materialService.update(id, p.getId(), p.getRole(), req, getIp(httpReq)));
    }

    /** DELETE /api/materials/{id} */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal p,
            HttpServletRequest httpReq) {
        materialService.delete(id, p.getId(), p.getRole(), getIp(httpReq));
        return ResponseEntity.ok(Map.of("message", "Tài liệu đã được xóa."));
    }

    /** POST /api/materials/{id}/download - Increment download counter */
    @PostMapping("/{id}/download")
    public ResponseEntity<Map<String, String>> download(@PathVariable String id) {
        materialService.incrementDownload(id);
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    // ── Admin: review & management ───────────────────────────────────────────────

    /** GET /api/materials/admin/all */
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Material>> getAllAdmin() {
        return ResponseEntity.ok(materialService.getAllForAdmin());
    }

    /** GET /api/materials/admin/pending */
    @GetMapping("/admin/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Material>> getPending() {
        return ResponseEntity.ok(materialService.getPendingReview());
    }

    /** POST /api/materials/admin/{id}/review */
    @PostMapping("/admin/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Material> review(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal p,
            @RequestBody Map<String, String> req,
            HttpServletRequest httpReq) {
        return ResponseEntity.ok(materialService.review(
                id, p.getId(), req.get("action"), req.get("reason"), getIp(httpReq)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private String getIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        return ip != null ? ip.split(",")[0].trim() : req.getRemoteAddr();
    }
}
