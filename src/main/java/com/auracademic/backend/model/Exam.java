package com.auracademic.backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
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
    private String teacherName;
    private String status; // "DRAFT", "PUBLISHED", "FINISHED"
    private String accessCode; // Mã phòng thi (vd: A1B2C3)
    private Long startTime; // Thời điểm bắt đầu (timestamp)
    private Long scheduledStartTime; // Thời điểm lên lịch bắt đầu tự động (timestamp)
    private String difficulty; // "EASY", "MEDIUM", "HARD", "EXPERT"
    private List<ExamVersion> versions;
    private List<String> extractedImages; // Base64 images extracted from source document
    @Field("isPractice")
    @JsonProperty("isPractice")
    private boolean isPractice; // Cờ đánh dấu đề thi nằm trong Ngân hàng Luyện tập (Practice Exam)
    private String folderId; // ID của thư mục trong Ngân hàng đề (ExamBankFolder)
    private String grade;    // Lớp học (VD: "Lớp 9") - có thể kế thừa từ folder
    private String subject;  // Môn học (VD: "Toán học") - có thể kế thừa từ folder
    @Field("isBankItem")
    @JsonProperty("isBankItem")
    private boolean isBankItem; // Cờ đánh dấu đề thi này được tạo RIÊNG cho Ngân hàng (không phải luồng thi chính thức)
    private String classroomId; // Liên kết bài thi với lớp học cụ thể (nếu có)
    private LocalDateTime createdAt = LocalDateTime.now();
    
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

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
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

    public Long getScheduledStartTime() {
        return scheduledStartTime;
    }

    public void setScheduledStartTime(Long scheduledStartTime) {
        this.scheduledStartTime = scheduledStartTime;
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

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public long getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(long submissionCount) {
        this.submissionCount = submissionCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isPractice() {
        return isPractice;
    }

    public void setPractice(boolean practice) {
        isPractice = practice;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public boolean isBankItem() {
        return isBankItem;
    }

    public void setBankItem(boolean bankItem) {
        isBankItem = bankItem;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
    
    public String getClassroomId() {
        return classroomId;
    }
    
    public void setClassroomId(String classroomId) {
        this.classroomId = classroomId;
    }
}
