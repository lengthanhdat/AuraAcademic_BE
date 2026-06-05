package com.auracademic.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Async
    public void sendVerificationEmail(String toEmail, String fullName, String token) {
        try {
            Context ctx = new Context();
            ctx.setVariable("name", fullName);
            ctx.setVariable("otp", token);
            ctx.setVariable("appName", "AuraAcademic");

            String html = templateEngine.process("email-verification", ctx);
            sendHtml(toEmail, "Xác thực tài khoản AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email xác thực tới {}: {}", toEmail, e.getMessage());
        }
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        try {
            Context ctx = new Context();
            ctx.setVariable("name", fullName);
            ctx.setVariable("resetUrl", frontendUrl + "/reset-password?token=" + token);
            ctx.setVariable("appName", "AuraAcademic");

            String html = templateEngine.process("password-reset", ctx);
            sendHtml(toEmail, "Đặt lại mật khẩu AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email đặt lại mật khẩu tới {}: {}", toEmail, e.getMessage());
        }
    }

    @Async
    public void sendSecurityAlertEmail(String toEmail, String fullName, String ipAddress, String userAgent, LocalDateTime loginTime) {
        try {
            String formattedTime = loginTime != null
                    ? loginTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                    : "Không xác định";

            Context ctx = new Context();
            ctx.setVariable("name", fullName != null ? fullName : "Người dùng");
            ctx.setVariable("ip", ipAddress != null ? ipAddress : "Không xác định");
            ctx.setVariable("device", userAgent != null ? userAgent : "Không xác định");
            ctx.setVariable("loginTime", formattedTime);

            String html = templateEngine.process("security-alert", ctx);
            sendHtml(toEmail, "Cảnh báo bảo mật AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email cảnh báo bảo mật tới {}: {}", toEmail, e.getMessage());
        }
    }

    @Async
    public void sendTwoFactorOtpEmail(String toEmail, String fullName, String otp, int ttlMinutes) {
        try {
            Context ctx = new Context();
            ctx.setVariable("name", fullName != null ? fullName : "Người dùng");
            ctx.setVariable("otp", otp);
            ctx.setVariable("ttlMinutes", ttlMinutes);

            String html = templateEngine.process("2fa-otp", ctx);
            sendHtml(toEmail, "Mã xác thực 2FA AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email OTP 2FA tới {}: {}", toEmail, e.getMessage());
        }
    }

    private void sendHtml(String to, String subject, String htmlContent) throws MessagingException, java.io.UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail, "AuraAcademic");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);
        mailSender.send(message);
        log.info("Email đã gửi thành công tới: {}", to);
    }


}
