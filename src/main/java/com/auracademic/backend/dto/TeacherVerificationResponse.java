package com.auracademic.backend.dto;

import java.time.LocalDateTime;

public class TeacherVerificationResponse {
    private String verificationStatus;
    private LocalDateTime submittedAt;
    private LocalDateTime verifiedAt;
    private String note;
    private String message;

    public TeacherVerificationResponse() {}

    public TeacherVerificationResponse(String verificationStatus, LocalDateTime submittedAt,
                                        LocalDateTime verifiedAt, String note, String message) {
        this.verificationStatus = verificationStatus;
        this.submittedAt = submittedAt;
        this.verifiedAt = verifiedAt;
        this.note = note;
        this.message = message;
    }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
