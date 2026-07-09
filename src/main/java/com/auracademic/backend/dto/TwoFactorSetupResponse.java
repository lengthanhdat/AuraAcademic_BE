package com.auracademic.backend.dto;


public class TwoFactorSetupResponse {
    private String secret;       // TOTP secret (hiển thị 1 lần)
    private String qrCodeUri;    // otpauth:// URI cho Google Authenticator
    private String qrCodeImage;  // Base64-encoded QR code PNG

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getQrCodeUri() {
        return qrCodeUri;
    }

    public void setQrCodeUri(String qrCodeUri) {
        this.qrCodeUri = qrCodeUri;
    }

    public String getQrCodeImage() {
        return qrCodeImage;
    }

    public void setQrCodeImage(String qrCodeImage) {
        this.qrCodeImage = qrCodeImage;
    }
}
