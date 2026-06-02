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
            String safeName = escapeHtml(fullName != null ? fullName : "Người dùng");
            String safeIp = escapeHtml(ipAddress != null ? ipAddress : "unknown");
            String safeAgent = escapeHtml(userAgent != null ? userAgent : "unknown");
            String time = loginTime != null
                    ? loginTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "unknown";

            String html = """
                    <div style="font-family:Arial,sans-serif;line-height:1.6;color:#0f172a">
                      <h2>Cảnh báo đăng nhập mới</h2>
                      <p>Xin chào %s, hệ thống vừa phát hiện một lần đăng nhập từ thiết bị hoặc địa chỉ IP mới.</p>
                      <ul>
                        <li><strong>IP:</strong> %s</li>
                        <li><strong>Thiết bị:</strong> %s</li>
                        <li><strong>Thời gian:</strong> %s</li>
                      </ul>
                      <p>Nếu đây không phải bạn, vui lòng đổi mật khẩu ngay và liên hệ quản trị viên.</p>
                    </div>
                    """.formatted(safeName, safeIp, safeAgent, escapeHtml(time));
            sendHtml(toEmail, "Cảnh báo bảo mật AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email cảnh báo bảo mật tới {}: {}", toEmail, e.getMessage());
        }
    }

    @Async
    public void sendTwoFactorOtpEmail(String toEmail, String fullName, String otp, int ttlMinutes) {
        try {
            String safeName = escapeHtml(fullName != null ? fullName : "Người dùng");
            String safeOtp = escapeHtml(otp);
            String html = """
                    <div style="font-family:Arial,sans-serif;line-height:1.6;color:#0f172a">
                      <h2>Mã xác thực hai bước AuraAcademic</h2>
                      <p>Xin chào %s, đây là mã OTP để xác thực thao tác bảo mật của bạn:</p>
                      <div style="font-size:32px;font-weight:800;letter-spacing:8px;background:#f1f5f9;border-radius:16px;padding:18px 24px;text-align:center;color:#0c2e5e">%s</div>
                      <p>Mã có hiệu lực trong %d phút. Không chia sẻ mã này với bất kỳ ai.</p>
                    </div>
                    """.formatted(safeName, safeOtp, ttlMinutes);
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

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
