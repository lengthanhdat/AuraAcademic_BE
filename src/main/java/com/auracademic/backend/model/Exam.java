package com.auracademic.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "exams")
public class Exam {
    @Id
    private String id;
    private String title;
    private int duration;
    private boolean shuffle;
    private boolean aiProctoring;
    private String teacherId;
    private String status; // "DRAFT", "PUBLISHED", "FINISHED"
    private String accessCode; // Mã phòng thi (vd: A1B2C3)
    private Long startTime; // Thời điểm bắt đầu (timestamp)
    private List<ExamVersion> versions;
    private List<String> extractedImages; // Base64 images extracted from source document
    
    @Transient
    private long submissionCount; // Number of students who have submitted results
}
