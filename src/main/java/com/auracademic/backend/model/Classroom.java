package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Document(collection = "classrooms")
public class Classroom {

    @Id
    private String id;
    
    private String name;
    private String description;
    private String code; // 6 characters unique code
    
    private String teacherId;
    private String teacherName;
    
    private List<String> studentIds = new ArrayList<>();
    private List<String> pendingStudentIds = new ArrayList<>();
    private List<String> removedStudentIds = new ArrayList<>();
    private List<Map<String, Object>> memberLogs = new ArrayList<>();
    
    private LocalDateTime createdAt;
    
    public Classroom() {
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    
    public String getTeacherId() { return teacherId; }
    public void setTeacherId(String teacherId) { this.teacherId = teacherId; }
    
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String teacherName) { this.teacherName = teacherName; }
    
    public List<String> getStudentIds() { return studentIds; }
    public void setStudentIds(List<String> studentIds) { this.studentIds = studentIds; }
    
    public List<String> getPendingStudentIds() { return pendingStudentIds; }
    public void setPendingStudentIds(List<String> pendingStudentIds) { this.pendingStudentIds = pendingStudentIds; }
    
    public List<String> getRemovedStudentIds() { return removedStudentIds; }
    public void setRemovedStudentIds(List<String> removedStudentIds) { this.removedStudentIds = removedStudentIds; }

    public List<Map<String, Object>> getMemberLogs() { return memberLogs; }
    public void setMemberLogs(List<Map<String, Object>> memberLogs) { this.memberLogs = memberLogs; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
