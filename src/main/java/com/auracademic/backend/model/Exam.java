package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    public boolean isAiProctoring() {
        return aiProctoring;
    }

    public void setAiProctoring(boolean aiProctoring) {
        this.aiProctoring = aiProctoring;
    }

    public String getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(String teacherId) {
        this.teacherId = teacherId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAccessCode() {
        return accessCode;
    }

    public void setAccessCode(String accessCode) {
        this.accessCode = accessCode;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public List<ExamVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<ExamVersion> versions) {
        this.versions = versions;
    }

    public List<String> getExtractedImages() {
        return extractedImages;
    }

    public void setExtractedImages(List<String> extractedImages) {
        this.extractedImages = extractedImages;
    }

    public long getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(long submissionCount) {
        this.submissionCount = submissionCount;
    }
}
