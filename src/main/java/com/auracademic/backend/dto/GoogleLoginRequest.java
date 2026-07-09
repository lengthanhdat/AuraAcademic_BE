package com.auracademic.backend.dto;

import jakarta.validation.constraints.NotBlank;

public class GoogleLoginRequest {
    /** Google ID Token từ frontend (Google Sign-In) */
    @NotBlank(message = "Google ID token không được để trống")
    private String idToken;

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }
}
