package com.auracademic.backend.dto;

public class TeacherVerificationRequest {
    private String proofType; // "LINK" or "DOCUMENT"
    private String proofUrl;
    private String description;

    public String getProofType() { return proofType; }
    public void setProofType(String proofType) { this.proofType = proofType; }

    public String getProofUrl() { return proofUrl; }
    public void setProofUrl(String proofUrl) { this.proofUrl = proofUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
