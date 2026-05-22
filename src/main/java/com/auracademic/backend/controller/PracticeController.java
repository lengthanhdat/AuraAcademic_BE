package com.auracademic.backend.controller;

import com.auracademic.backend.model.Exam;
import com.auracademic.backend.model.ExamVersion;
import com.auracademic.backend.model.Option;
import com.auracademic.backend.model.PracticeResult;
import com.auracademic.backend.model.Question;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.PracticeResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private PracticeResultRepository practiceResultRepository;

    @GetMapping("/exams")
    public ResponseEntity<?> getPracticeExams() {
        try {
            List<Exam> exams = examRepository.findByIsPractice(true).stream()
                    .filter(exam -> !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                            && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus())))
                    .map(this::withoutCorrectAnswers)
                    .toList();
            return ResponseEntity.ok(exams);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching practice exams: " + e.getMessage());
        }
    }

    @GetMapping("/exams/{id}")
    public ResponseEntity<?> getPracticeExamDetails(@PathVariable String id) {
        return examRepository.findById(id)
                .filter(Exam::isPractice)
                .filter(exam -> !"DRAFT".equalsIgnoreCase(String.valueOf(exam.getStatus()))
                        && !"ARCHIVED".equalsIgnoreCase(String.valueOf(exam.getStatus())))
                .map(exam -> ResponseEntity.ok(withoutCorrectAnswers(exam)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/exams/{id}/toggle")
    public ResponseEntity<?> togglePracticeMode(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        try {
            boolean isPractice = body.getOrDefault("isPractice", false);
            return examRepository.findById(id).map(exam -> {
                exam.setPractice(isPractice);
                examRepository.save(exam);
                return ResponseEntity.ok(Map.of("message", "Đã cập nhật chế độ ôn tập.", "isPractice", isPractice));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error toggling practice mode: " + e.getMessage());
        }
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submitPracticeResult(@RequestBody PracticeResult result) {
        try {
            Exam exam = examRepository.findById(result.getExamId())
                    .filter(Exam::isPractice)
                    .orElseThrow(() -> new IllegalArgumentException("Practice exam not found."));

            List<Question> questions = exam.getVersions() != null && !exam.getVersions().isEmpty()
                    ? exam.getVersions().get(0).getQuestions()
                    : List.of();

            int correct = 0;
            Map<String, String> answers = result.getAnswers() != null ? result.getAnswers() : Map.of();
            for (Question question : questions) {
                String submittedOptionId = answers.get(question.getId());
                if (submittedOptionId == null || question.getOptions() == null) continue;
                boolean isCorrect = question.getOptions().stream()
                        .anyMatch(option -> option.isCorrect() && submittedOptionId.equals(option.getId()));
                if (isCorrect) correct++;
            }

            int total = questions.size();
            result.setExamTitle(exam.getTitle());
            result.setVersionCode(exam.getVersions() != null && !exam.getVersions().isEmpty() ? exam.getVersions().get(0).getVersionCode() : result.getVersionCode());
            result.setCorrectAnswers(correct);
            result.setTotalQuestions(total);
            result.setScore(total > 0 ? Math.round((correct * 10.0 / total) * 10.0) / 10.0 : 0);
            result.setSubmittedAt(System.currentTimeMillis());

            PracticeResult savedResult = practiceResultRepository.save(result);
            return ResponseEntity.ok(savedResult);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error saving practice result: " + e.getMessage());
        }
    }

    @GetMapping("/results/student/{studentId}")
    public ResponseEntity<?> getStudentPracticeResults(@PathVariable String studentId) {
        try {
            List<PracticeResult> results = practiceResultRepository.findByStudentId(studentId);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error fetching practice results: " + e.getMessage());
        }
    }

    @GetMapping("/results/{resultId}")
    public ResponseEntity<?> getPracticeResult(@PathVariable String resultId) {
        return practiceResultRepository.findById(resultId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }


    @GetMapping("/results/{resultId}/exam")
    public ResponseEntity<?> getPracticeResultExam(@PathVariable String resultId) {
        return practiceResultRepository.findById(resultId)
                .flatMap(result -> examRepository.findById(result.getExamId()))
                .filter(Exam::isPractice)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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