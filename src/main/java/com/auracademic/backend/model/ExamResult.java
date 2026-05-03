package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "exam_results")
public class ExamResult {
    @Id
    private String id;
    private String examId;
    private String studentId;
    private String studentName;
    private String versionCode;
    private String examTitle;
    private double score;
    private int correctAnswers;
    private int totalQuestions;
    private Long submittedAt;
    private Map<String, String> answers; // Map<QuestionID, OptionID>
}
