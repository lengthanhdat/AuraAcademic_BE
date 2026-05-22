package com.auracademic.backend.controller;

import com.auracademic.backend.model.Exam;
import com.auracademic.backend.model.ExamBankFolder;
import com.auracademic.backend.model.ExamVersion;
import com.auracademic.backend.model.Option;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.repository.ExamBankFolderRepository;
import com.auracademic.backend.repository.ExamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/exam-bank")
public class ExamBankController {

    @Autowired
    private ExamBankFolderRepository folderRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private com.auracademic.backend.repository.PracticeResultRepository practiceResultRepository;

    @GetMapping("/teacher/{teacherId}/folders")
    public ResponseEntity<List<ExamBankFolder>> getFolders(@PathVariable String teacherId) {
        // Now returns ALL global folders since Admin creates them.
        return ResponseEntity.ok(folderRepository.findAll());
    }

    @GetMapping("/public/folders")
    public ResponseEntity<List<ExamBankFolder>> getAllPublicFolders() {
        List<ExamBankFolder> folders = folderRepository.findAll().stream()
                .filter(folder -> examRepository.findByFolderId(folder.getId()).stream()
                        .anyMatch(exam -> exam.isPractice()
                                && !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                                && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus()))))
                .toList();
        return ResponseEntity.ok(folders);
    }

    @GetMapping("/exams")
    public ResponseEntity<List<java.util.Map<String, Object>>> getAllBankExamsForManagement() {
        // Fetch all practice exams including DRAFT
        List<java.util.Map<String, Object>> result = examRepository.findByIsPractice(true).stream()
            .map(exam -> {
                java.util.Map<String, Object> dto = new java.util.LinkedHashMap<>();
                dto.put("id", exam.getId());
                dto.put("title", exam.getTitle());
                dto.put("teacherName", exam.getTeacherName());
                dto.put("difficulty", exam.getDifficulty());
                dto.put("duration", exam.getDuration());
                dto.put("status", exam.getStatus());
                dto.put("createdAt", exam.getCreatedAt());
                int qCount = 0;
                if (exam.getVersions() != null && !exam.getVersions().isEmpty()
                        && exam.getVersions().get(0).getQuestions() != null) {
                    qCount = exam.getVersions().get(0).getQuestions().size();
                }
                dto.put("questionCount", qCount);
                long plays = practiceResultRepository.countByExamId(exam.getId());
                dto.put("submissionCount", plays);
                dto.put("subject", exam.getSubject());
                return dto;
            })
            .sorted(java.util.Comparator.comparing(
                (java.util.Map<String, Object> m) -> (LocalDateTime) m.get("createdAt"),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/public/exams")
    public ResponseEntity<List<java.util.Map<String, Object>>> getAllPublicExams() {
        List<ExamBankFolder> allFolders = folderRepository.findAll();
        java.util.Map<String, ExamBankFolder> folderMap = new java.util.HashMap<>();
        for (ExamBankFolder f : allFolders) folderMap.put(f.getId(), f);

        List<java.util.Map<String, Object>> result = examRepository.findByIsPractice(true).stream()
            .filter(exam -> !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus())))
            .map(exam -> {
                java.util.Map<String, Object> dto = new java.util.LinkedHashMap<>();
                dto.put("id", exam.getId());
                dto.put("title", exam.getTitle());
                dto.put("teacherName", exam.getTeacherName());
                dto.put("difficulty", exam.getDifficulty());
                dto.put("duration", exam.getDuration());
                dto.put("folderId", exam.getFolderId());
                dto.put("createdAt", exam.getCreatedAt());
                int qCount = 0;
                if (exam.getVersions() != null && !exam.getVersions().isEmpty()
                        && exam.getVersions().get(0).getQuestions() != null) {
                    qCount = exam.getVersions().get(0).getQuestions().size();
                }
                dto.put("questionCount", qCount);
                long plays = practiceResultRepository.countByExamId(exam.getId());
                dto.put("submissionCount", plays);
                ExamBankFolder folder = exam.getFolderId() != null ? folderMap.get(exam.getFolderId()) : null;
                // Prefer grade/subject set directly on exam, then fall back to folder
                String grade   = exam.getGrade()   != null ? exam.getGrade()   : (folder != null ? folder.getGrade()   : null);
                String subject = exam.getSubject() != null ? exam.getSubject() : (folder != null ? folder.getSubject() : null);
                dto.put("grade", grade);
                dto.put("subject", subject);
                dto.put("folderName", folder != null ? folder.getName() : null);
                return dto;
            })
            .sorted(java.util.Comparator.comparingLong(
                (java.util.Map<String, Object> m) -> (long)(m.getOrDefault("submissionCount", 0L))).reversed())
            .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/public/stats")
    public ResponseEntity<java.util.Map<String, Object>> getPublicStats() {
        List<ExamBankFolder> allFolders = folderRepository.findAll();
        java.util.Map<String, ExamBankFolder> folderMap = new java.util.HashMap<>();
        for (ExamBankFolder f : allFolders) folderMap.put(f.getId(), f);

        java.util.Map<String, Long> bySubject = new java.util.TreeMap<>();
        java.util.Map<String, Long> byGrade = new java.util.LinkedHashMap<>();

        examRepository.findByIsPractice(true).stream()
            .filter(exam -> !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus())))
            .forEach(exam -> {
                ExamBankFolder folder = exam.getFolderId() != null ? folderMap.get(exam.getFolderId()) : null;
                // Prefer grade/subject set directly on exam, then fall back to folder
                String grade   = exam.getGrade()   != null ? exam.getGrade()   : (folder != null ? folder.getGrade()   : null);
                String subject = exam.getSubject() != null ? exam.getSubject() : (folder != null ? folder.getSubject() : null);
                if (subject != null) bySubject.merge(subject, 1L, Long::sum);
                if (grade   != null) byGrade.merge(grade,     1L, Long::sum);
            });

        return ResponseEntity.ok(java.util.Map.of("bySubject", bySubject, "byGrade", byGrade));
    }

    @GetMapping("/folders/{id}")
    public ResponseEntity<ExamBankFolder> getFolder(@PathVariable String id) {
        return folderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/folders")
    public ResponseEntity<ExamBankFolder> createFolder(@RequestBody ExamBankFolder folder) {
        folder.setId(null);
        folder.setName(folder.getName() != null ? folder.getName().trim() : "");
        folder.setDescription(folder.getDescription() != null ? folder.getDescription().trim() : "");
        if (folder.getGrade() != null) folder.setGrade(folder.getGrade().trim());
        if (folder.getSubject() != null) folder.setSubject(folder.getSubject().trim());
        folder.setTeacherId("ADMIN"); // Mark as global
        folder.setCreatedAt(LocalDateTime.now());
        ExamBankFolder saved = folderRepository.save(folder);
        return ResponseEntity.ok(saved);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/folders/{id}")
    public ResponseEntity<ExamBankFolder> updateFolder(@PathVariable String id, @RequestBody ExamBankFolder updateReq) {
        Optional<ExamBankFolder> opt = folderRepository.findById(id);
        if (opt.isPresent()) {
            ExamBankFolder folder = opt.get();
            if (updateReq.getName() != null) folder.setName(updateReq.getName().trim());
            if (updateReq.getDescription() != null) folder.setDescription(updateReq.getDescription().trim());
            if (updateReq.getGrade() != null) folder.setGrade(updateReq.getGrade().trim());
            if (updateReq.getSubject() != null) folder.setSubject(updateReq.getSubject().trim());
            return ResponseEntity.ok(folderRepository.save(folder));
        }
        return ResponseEntity.notFound().build();
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/folders/{id}")
    public ResponseEntity<?> deleteFolder(@PathVariable String id) {
        folderRepository.deleteById(id);
        List<Exam> examsInFolder = examRepository.findByFolderId(id);
        for (Exam e : examsInFolder) {
            e.setFolderId(null);
            examRepository.save(e);
        }
        return ResponseEntity.ok().build();
    }


    @GetMapping("/folders/{folderId}/teacher-items")
    public ResponseEntity<List<Exam>> getTeacherItemsInFolder(@PathVariable String folderId) {
        List<Exam> items = examRepository.findByFolderId(folderId).stream()
                .filter(exam -> exam.isPractice() || exam.isBankItem())
                .toList();
        for (Exam exam : items) {
            long count = practiceResultRepository.countByExamId(exam.getId());
            exam.setSubmissionCount(count);
        }
        return ResponseEntity.ok(items);
    }
    @GetMapping("/folders/{folderId}/items")
    public ResponseEntity<List<Exam>> getItemsInFolder(@PathVariable String folderId) {
        List<Exam> items = examRepository.findByFolderId(folderId).stream()
                .filter(exam -> exam.isPractice()
                        && !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                        && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus())))
                .map(this::withoutCorrectAnswers)
                .toList();
        for (Exam exam : items) {
            long count = practiceResultRepository.countByExamId(exam.getId());
            exam.setSubmissionCount(count);
        }
        return ResponseEntity.ok(items);
    }


    @PatchMapping("/items/{id}/status")
    public ResponseEntity<?> updateBankItemStatus(@PathVariable String id, @RequestBody java.util.Map<String, String> body) {
        String status = body.getOrDefault("status", "").trim().toUpperCase();
        if (!List.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status)) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid status."));
        }
        return examRepository.findById(id)
                .map(exam -> {
                    exam.setStatus(status);
                    examRepository.save(exam);
                    return ResponseEntity.ok(java.util.Map.of("id", exam.getId(), "status", exam.getStatus()));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    @DeleteMapping("/items/{id}")
    public ResponseEntity<?> deleteBankItem(@PathVariable String id) {
        examRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/teacher/{teacherId}/published-items")
    public ResponseEntity<List<Exam>> getPublishedItems(@PathVariable String teacherId) {
        List<Exam> published = examRepository.findByTeacherId(teacherId).stream()
                .filter(e -> (e.isPractice() || e.isBankItem())
                        && !"DRAFT".equalsIgnoreCase(String.valueOf(e.getStatus()))
                        && !"ARCHIVED".equalsIgnoreCase(String.valueOf(e.getStatus())))
                .map(this::withoutCorrectAnswers)
                .toList();
        return ResponseEntity.ok(published);
    }

    private Exam withoutCorrectAnswers(Exam exam) {
        Exam copy = copyExamShell(exam);
        if (exam.getVersions() != null) {
            copy.setVersions(exam.getVersions().stream().map(version -> {
                ExamVersion versionCopy = new ExamVersion();
                versionCopy.setVersionCode(version.getVersionCode());
                if (version.getQuestions() != null) {
                    versionCopy.setQuestions(version.getQuestions().stream().map(this::withoutCorrectAnswer).toList());
                }
                return versionCopy;
            }).toList());
        }
        return copy;
    }

    private Question withoutCorrectAnswer(Question question) {
        Question copy = new Question();
        copy.setId(question.getId());
        copy.setType(question.getType());
        copy.setText(question.getText());
        copy.setImageUrl(question.getImageUrl());
        if (question.getOptions() != null) {
            copy.setOptions(question.getOptions().stream()
                    .map(option -> new Option(option.getId(), option.getText(), false))
                    .toList());
        }
        return copy;
    }

    private Exam copyExamShell(Exam exam) {
        Exam copy = new Exam();
        copy.setId(exam.getId());
        copy.setTitle(exam.getTitle());
        copy.setDuration(exam.getDuration());
        copy.setShuffle(exam.isShuffle());
        copy.setAiProctoring(false);
        copy.setTeacherId(exam.getTeacherId());
        copy.setTeacherName(exam.getTeacherName());
        copy.setStatus(exam.getStatus());
        copy.setDifficulty(exam.getDifficulty());
        copy.setExtractedImages(exam.getExtractedImages());
        copy.setPractice(exam.isPractice());
        copy.setFolderId(exam.getFolderId());
        copy.setBankItem(exam.isBankItem());
        copy.setCreatedAt(exam.getCreatedAt());
        return copy;
    }
}