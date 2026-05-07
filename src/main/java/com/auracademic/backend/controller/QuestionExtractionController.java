package com.auracademic.backend.controller;

import com.auracademic.backend.dto.ParsedQuestion;
import com.auracademic.backend.service.QuestionExtractionService;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/questions")

public class QuestionExtractionController {

    @Autowired
    private QuestionExtractionService extractionService;

    /**
     * Upload file DOCX hoac PDF, trich xuat tat ca cau hoi co trong file.
     * KHONG dung AI — chi dung document parsing.
     *
     * POST /api/questions/extract
     * multipart/form-data: file
     */
    @PostMapping("/extract")
    public ResponseEntity<?> extractQuestions(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vui long chon file truoc khi upload"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.toLowerCase().endsWith(".docx") && !filename.toLowerCase().endsWith(".pdf"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chi ho tro file DOCX va PDF"));
        }

        try {
            List<ParsedQuestion> questions = extractionService.extractFromFile(file);

            if (questions.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "questions", questions,
                    "message", "Khong tim thay cau hoi nao trong file. Vui long kiem tra dinh dang file (Cau 1:, A., B., C., D.)"
                ));
            }

            return ResponseEntity.ok(Map.of(
                "questions", questions,
                "total", questions.size(),
                "message", "Trich xuat thanh cong " + questions.size() + " cau hoi"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "Loi khi xu ly file: " + e.getMessage()
            ));
        }
    }
}
