package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "violation_logs")
public class ViolationLog {
    @Id
    private String id;
    private String examCode;
    private String studentId;
    private String studentName;
    private String type;
    private long timestamp;
    private String videoUrl;    // Legacy: URL file video
    private String videoBase64; // Mới: video mã hóa Base64 để hiển thị trực tiếp

    public ViolationLog() {}

    public ViolationLog(String examCode, String studentId, String studentName, String type, String videoUrl, String videoBase64, long timestamp) {
        this.examCode = examCode;
        this.studentId = studentId;
        this.studentName = studentName;
        this.type = type;
        this.videoUrl = videoUrl;
        this.videoBase64 = videoBase64;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getExamCode() { return examCode; }
    public void setExamCode(String examCode) { this.examCode = examCode; }
    
    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public String getVideoBase64() { return videoBase64; }
    public void setVideoBase64(String videoBase64) { this.videoBase64 = videoBase64; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
