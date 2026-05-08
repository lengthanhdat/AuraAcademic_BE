package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "notifications")
public class Notification {
    @Id
    private String id;
    private String userId; // Specific user ID or "ALL" for global system notifications
    private String title;
    private String content;
    private String type; // "INFO", "WARNING", "EXAM", "MATERIAL", "SYSTEM"
    private boolean read = false; // For personal notifications
    private List<String> readBy = new ArrayList<>(); // Track users who read global ("ALL") notifications
    private LocalDateTime createdAt = LocalDateTime.now();

    public Notification() {}

    public Notification(String userId, String title, String content, String type) {
        this.userId = userId;
        this.title = title;
        this.content = content;
        this.type = type;
        this.read = false;
        this.readBy = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public List<String> getReadBy() { return readBy; }
    public void setReadBy(List<String> readBy) { this.readBy = readBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
