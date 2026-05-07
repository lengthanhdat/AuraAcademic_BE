package com.auracademic.backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.List;

/**
 * Lưu trạng thái của mỗi job xử lý AI trong MongoDB (collection: ai_jobs).
 * Vòng đời: PROCESSING → DONE | FAILED
 */
@Document(collection = "ai_jobs")
public class AiJob {

    @Id
    private String id;
    private String status;
    private List<Question> questions;
    private String extractedText;
    private List<String> extractedImages;
    private String errorMessage;
    private String provider;
    private long processingTimeMs;

    @Indexed
    private long createdAt;

    public AiJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Question> getQuestions() { return questions; }
    public void setQuestions(List<Question> questions) { this.questions = questions; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public List<String> getExtractedImages() { return extractedImages; }
    public void setExtractedImages(List<String> extractedImages) { this.extractedImages = extractedImages; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
