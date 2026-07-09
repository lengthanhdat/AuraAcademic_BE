package com.auracademic.backend.controller;

import com.auracademic.backend.model.Classroom;
import com.auracademic.backend.model.Exam;
import com.auracademic.backend.model.ExamResult;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.ClassroomRepository;
import com.auracademic.backend.repository.ExamRepository;
import com.auracademic.backend.repository.ExamResultRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/teacher/dashboard")
@PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
public class TeacherDashboardController {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private ExamResultRepository resultRepository;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats(@AuthenticationPrincipal UserPrincipal principal) {
        String teacherId = principal.getId();
        Map<String, Object> response = new HashMap<>();

        // 1. Get Teacher's Exams
        List<Exam> exams = examRepository.findByTeacherId(teacherId).stream()
                .filter(e -> !e.isPractice() && !e.isBankItem() && !e.isTemplate())
                .collect(Collectors.toList());

        List<String> examCodes = exams.stream()
                .map(Exam::getAccessCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 2. Fetch Recent Submissions (Latest 5)
        List<Map<String, Object>> recentSubmissions = new ArrayList<>();
        if (!examCodes.isEmpty()) {
            List<ExamResult> allResults = new ArrayList<>();
            for (String code : examCodes) {
                allResults.addAll(resultRepository.findByExamId(code));
            }
            // Sort by submittedAt descending
            allResults.sort((a, b) -> Long.compare(b.getSubmittedAt(), a.getSubmittedAt()));
            
            int limit = Math.min(5, allResults.size());
            for (int i = 0; i < limit; i++) {
                ExamResult res = allResults.get(i);
                Map<String, Object> map = new HashMap<>();
                map.put("id", res.getId());
                map.put("studentName", res.getStudentName());
                map.put("score", res.getScore());
                map.put("maxScore", 10.0);
                map.put("submittedAt", res.getSubmittedAt());
                
                // Find exam title
                String examTitle = exams.stream()
                        .filter(e -> res.getExamId().equals(e.getAccessCode()))
                        .findFirst()
                        .map(Exam::getTitle)
                        .orElse("Bài thi");
                map.put("examTitle", examTitle);
                recentSubmissions.add(map);
            }
        }
        response.put("recentSubmissions", recentSubmissions);

        // 3. Fetch Pending Approvals across classrooms
        List<Classroom> classrooms = classroomRepository.findByTeacherIdOrderByCreatedAtDesc(teacherId);
        List<Map<String, Object>> pendingApprovals = new ArrayList<>();
        
        for (Classroom c : classrooms) {
            if (c.getPendingStudentIds() != null && !c.getPendingStudentIds().isEmpty()) {
                for (String studentId : c.getPendingStudentIds()) {
                    userRepository.findById(studentId).ifPresent(u -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("classroomId", c.getId());
                        map.put("classroomName", c.getName());
                        map.put("studentId", u.getId());
                        map.put("studentName", u.getFullName() != null ? u.getFullName() : u.getEmail());
                        map.put("studentEmail", u.getEmail());
                        pendingApprovals.add(map);
                    });
                }
            }
        }
        response.put("pendingApprovals", pendingApprovals);

        // 4. Performance Analytics (Average score of last 5 exams that have results)
        List<Map<String, Object>> performanceChart = new ArrayList<>();
        if (!examCodes.isEmpty()) {
            // Sort exams by creation time (descending) to get recent ones
            List<Exam> recentExams = exams.stream()
                    .filter(e -> e.getCreatedAt() != null)
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .collect(Collectors.toList());
                    
            int count = 0;
            for (Exam exam : recentExams) {
                if (count >= 5) break;
                if (exam.getAccessCode() == null) continue;
                
                List<ExamResult> results = resultRepository.findByExamId(exam.getAccessCode());
                if (!results.isEmpty()) {
                    double totalRatio = 0;
                    for (ExamResult r : results) {
                        double max = 10.0;
                        totalRatio += (r.getScore() / max) * 10.0;
                    }
                    double avgScore = totalRatio / results.size();
                    
                    Map<String, Object> chartData = new HashMap<>();
                    chartData.put("examId", exam.getId());
                    chartData.put("title", exam.getTitle());
                    // Round to 1 decimal place
                    chartData.put("avgScore", Math.round(avgScore * 10.0) / 10.0);
                    chartData.put("submissionCount", results.size());
                    
                    // Add to beginning to reverse order for chart (oldest to newest left-to-right)
                    performanceChart.add(0, chartData);
                    count++;
                }
            }
        }
        response.put("performanceChart", performanceChart);

        return ResponseEntity.ok(response);
    }
}
