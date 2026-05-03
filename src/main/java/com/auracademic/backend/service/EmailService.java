package com.auracademic.backend.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

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
            sendHtml(toEmail, "✅ Xác thực tài khoản AuraAcademic", html);
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
            sendHtml(toEmail, "🔑 Đặt lại mật khẩu AuraAcademic", html);
        } catch (Exception e) {
            log.error("Không thể gửi email reset password tới {}: {}", toEmail, e.getMessage());
        }
    }

    private void sendHtml(String to, String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);
        mailSender.send(message);
        log.info("Email đã gửi thành công tới: {}", to);
    }
}
