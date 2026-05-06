package com.auracademic.backend.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auracademic.backend.dto.*;
import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.exception.TwoFactorRequiredException;
import com.auracademic.backend.model.RefreshToken;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.RefreshTokenRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TwoFactorService twoFactorService;
    private final AuditLogService auditLogService;
    private final UserMapper userMapper;
    private final SettingService settingService;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository, JwtTokenProvider jwtTokenProvider, PasswordEncoder passwordEncoder, EmailService emailService, TwoFactorService twoFactorService, AuditLogService auditLogService, UserMapper userMapper, SettingService settingService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.twoFactorService = twoFactorService;
        this.auditLogService = auditLogService;
        this.userMapper = userMapper;
        this.settingService = settingService;
    }


    @Value("${app.google.client-id}")
    private String googleClientId;

    @Value("${app.security.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${app.security.lock-duration-minutes:15}")
    private int lockDurationMinutes;

    @Value("${app.auth.email-verification-ttl-minutes:1440}")
    private int emailVerificationTtl;

    @Value("${app.auth.password-reset-ttl-minutes:60}")
    private int passwordResetTtl;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    // ─── Register ─────────────────────────────────────────────────────────────

    public void register(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthException("Email đã được đăng ký");
        }

        String verificationToken = String.format("%06d", new java.util.Random().nextInt(1000000));

        User user = buildLocalUser(request.getFullName(), request.getEmail(),
                passwordEncoder.encode(request.getPassword()), request.getRole());
        user.setEmailVerificationToken(verificationToken);
        user.setEmailVerificationExpiry(LocalDateTime.now().plusMinutes(emailVerificationTtl));

        userRepository.save(user);
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), verificationToken);
        auditLogService.log(user.getId(), user.getEmail(), "REGISTER", ipAddress, null, true, null);

        log.info("Người dùng mới đã đăng ký: {}", user.getEmail());
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    auditLogService.log(null, request.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "User not found");
                    return new AuthException("Email hoặc mật khẩu không chính xác");
                });

        // Kiểm tra account lock
        checkAccountLock(user);

        if (settingService.getBoolean("maintenance_mode", false) && !"admin".equals(user.getRole())) {
            throw new AuthException("Hệ thống đang bảo trì, vui lòng quay lại sau.");
        }

        // Kiểm tra email verified
        if (!user.isEmailVerified()) {
            throw new AuthException("Tài khoản chưa được xác thực email. Vui lòng kiểm tra hộp thư của bạn.");
        }

        // Verify password (hỗ trợ migration từ plaintext)
        boolean passwordOk = verifyPassword(request.getPassword(), user);
        if (!passwordOk) {
            handleFailedLogin(user, ipAddress, userAgent);
            throw new AuthException("Email hoặc mật khẩu không chính xác");
        }

        // Kiểm tra 2FA
        if (user.isTwoFactorEnabled()) {
            if (request.getTwoFactorCode() == null || request.getTwoFactorCode().isBlank()) {
                throw new TwoFactorRequiredException("Vui lòng nhập mã xác thực 2FA");
            }
            if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), request.getTwoFactorCode())) {
                auditLogService.log(user.getId(), user.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "Invalid 2FA code");
                throw new AuthException("Mã xác thực 2FA không đúng");
            }
        }

        // Reset failed attempts
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        auditLogService.log(user.getId(), user.getEmail(), "LOGIN", ipAddress, userAgent, true, null);

        return buildAuthResponse(user, ipAddress, userAgent);
    }

    // ─── Refresh Token ────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refreshToken(String refreshTokenStr, String ipAddress, String userAgent) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(refreshTokenStr)
                .orElseThrow(() -> new AuthException("Refresh token không hợp lệ"));

        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new AuthException("Refresh token đã hết hạn, vui lòng đăng nhập lại");
        }

        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        // Token rotation — xóa token cũ
        refreshTokenRepository.delete(refreshToken);

        return buildAuthResponse(user, ipAddress, userAgent);
    }

    // ─── Logout ───────────────────────────────────────────────────────────────

    public void logout(String refreshTokenStr, String userId, String ipAddress) {
        refreshTokenRepository.findByToken(refreshTokenStr)
                .ifPresent(refreshTokenRepository::delete);
        auditLogService.log(userId, null, "LOGOUT", ipAddress, null, true, null);
    }

    // ─── Email Verification ───────────────────────────────────────────────────

    public void verifyEmail(String email, String token) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new AuthException("Email không tồn tại"));
        if (!token.equals(user.getEmailVerificationToken())) throw new AuthException("Mã xác thực không hợp lệ");
        if (user.isEmailVerified()) return; // avoid optional throwing if already verified?

        if (user.getEmailVerificationExpiry().isBefore(LocalDateTime.now())) {
            throw new AuthException("Token xác thực đã hết hạn. Vui lòng yêu cầu gửi lại email.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiry(null);
        userRepository.save(user);
        log.info("Email xác thực thành công: {}", user.getEmail());
    }

    public void resendVerification(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("Email không tồn tại"));

        if (user.isEmailVerified()) {
            throw new AuthException("Tài khoản đã được xác thực");
        }

        String token = String.format("%06d", new java.util.Random().nextInt(1000000));
        user.setEmailVerificationToken(token);
        user.setEmailVerificationExpiry(LocalDateTime.now().plusMinutes(emailVerificationTtl));
        userRepository.save(user);
        emailService.sendVerificationEmail(email, user.getFullName(), token);
    }

    // ─── Forgot / Reset Password ──────────────────────────────────────────────

    public void forgotPassword(String email, String ipAddress) {
        // Luôn trả về OK để tránh user enumeration attack (OWASP)
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = String.format("%06d", new java.util.Random().nextInt(1000000));
            user.setPasswordResetToken(token);
            user.setPasswordResetExpiry(LocalDateTime.now().plusMinutes(passwordResetTtl));
            userRepository.save(user);
            emailService.sendPasswordResetEmail(email, user.getFullName(), token);
            auditLogService.log(user.getId(), email, "PASSWORD_RESET_REQUEST", ipAddress, null, true, null);
        });
    }

    public void resetPassword(ResetPasswordRequest request, String ipAddress) {
        User user = userRepository.findByPasswordResetToken(request.getToken())
                .orElseThrow(() -> new AuthException("Token đặt lại mật khẩu không hợp lệ hoặc đã hết hạn"));

        if (user.getPasswordResetExpiry().isBefore(LocalDateTime.now())) {
            throw new AuthException("Token đặt lại mật khẩu đã hết hạn");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiry(null);
        // Hủy tất cả refresh tokens sau khi đổi mật khẩu
        refreshTokenRepository.deleteByUserId(user.getId());
        userRepository.save(user);

        auditLogService.log(user.getId(), user.getEmail(), "PASSWORD_RESET", ipAddress, null, true, null);
        log.info("Mật khẩu đã được đặt lại cho: {}", user.getEmail());
    }

    // ─── Google OAuth2 ────────────────────────────────────────────────────────

    public AuthResponse loginWithGoogle(String idTokenStr, String ipAddress, String userAgent) {
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenStr);
            if (idToken == null) {
                throw new AuthException("Google ID token không hợp lệ");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();
            String googleId = payload.getSubject();
            String email = payload.getEmail();
            String name = (String) payload.get("name");
            String pictureUrl = (String) payload.get("picture");

            // Tìm hoặc tạo user
            User user = userRepository.findByProviderAndProviderId("google", googleId)
                    .orElseGet(() -> userRepository.findByEmail(email)
                            .map(existingUser -> {
                                // Link Google account nếu email đã tồn tại
                                existingUser.setProvider("google");
                                existingUser.setProviderId(googleId);
                                existingUser.setEmailVerified(true);
                                return userRepository.save(existingUser);
                            })
                            .orElseGet(() -> {
                                User newUser = buildOAuth2User(name, email, "google", googleId, pictureUrl);
                                return userRepository.save(newUser);
                            }));

            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            auditLogService.log(user.getId(), user.getEmail(), "GOOGLE_LOGIN", ipAddress, userAgent, true, null);
            return buildAuthResponse(user, ipAddress, userAgent);

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi xác thực Google token: {}", e.getMessage());
            throw new AuthException("Không thể xác thực với Google");
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void checkAccountLock(User user) {
        if (user.isAccountLocked()) {
            if (user.getLockExpiry() != null && user.getLockExpiry().isBefore(LocalDateTime.now())) {
                // Mở khóa tự động
                user.setAccountLocked(false);
                user.setFailedLoginAttempts(0);
                user.setLockExpiry(null);
                userRepository.save(user);
            } else {
                throw new AuthException("Tài khoản tạm thời bị khóa do đăng nhập sai quá nhiều lần. Vui lòng thử lại sau.");
            }
        }
    }

    private boolean verifyPassword(String rawPassword, User user) {
        String stored = user.getPassword();
        if (stored == null) return false;

        if (stored.startsWith("$2a$") || stored.startsWith("$2b$")) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        // Migration: password cũ plaintext
        if (rawPassword.equals(stored)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    private void handleFailedLogin(User user, String ipAddress, String userAgent) {
        boolean enforceLock = settingService.getBoolean("lock_after_5_fails", true);
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (enforceLock && attempts >= maxFailedAttempts) {
            user.setAccountLocked(true);
            user.setLockExpiry(LocalDateTime.now().plusMinutes(lockDurationMinutes));
            auditLogService.log(user.getId(), user.getEmail(), "ACCOUNT_LOCKED", ipAddress, userAgent, false, "Locked after " + attempts + " failed attempts");
        }
        
        userRepository.save(user);
        auditLogService.log(user.getId(), user.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "Invalid password");
    }

    private AuthResponse buildAuthResponse(User user, String ipAddress, String userAgent) {
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshTokenStr = jwtTokenProvider.generateRefreshToken(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setToken(refreshTokenStr);
        refreshToken.setDeviceInfo(userAgent);
        refreshToken.setIpAddress(ipAddress);
        refreshToken.setExpiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000));
        refreshToken.setCreatedAt(LocalDateTime.now());
        refreshTokenRepository.save(refreshToken);

        AuthResponse authResponse = new AuthResponse();
        authResponse.setAccessToken(accessToken);
        authResponse.setRefreshToken(refreshTokenStr);
        authResponse.setTokenType("Bearer");
        authResponse.setExpiresIn(jwtTokenProvider.getAccessTokenExpiry());
        authResponse.setUser(userMapper.toProfile(user));
        return authResponse;
    }

    public UserProfileResponse mapToProfile(User user) {
        return userMapper.toProfile(user);
    }

    // ─── Internal User Builders ───────────────────────────────────────────────

    private User buildLocalUser(String fullName, String email, String password, String role) {
        User u = new User();
        u.setFullName(fullName);
        u.setEmail(email);
        u.setPassword(password);
        u.setRole(role != null ? role : "student");
        u.setProvider("local");
        u.setEmailVerified(false);
        u.setTwoFactorEnabled(false);
        u.setAccountLocked(false);
        u.setFailedLoginAttempts(0);
        u.setCreatedAt(LocalDateTime.now());
        return u;
    }

    private User buildOAuth2User(String fullName, String email, String provider, String providerId, String avatarUrl) {
        User u = new User();
        u.setFullName(fullName);
        u.setEmail(email);
        u.setRole("student");
        u.setProvider(provider);
        u.setProviderId(providerId);
        u.setAvatarUrl(avatarUrl);
        u.setEmailVerified(true);
        u.setTwoFactorEnabled(false);
        u.setAccountLocked(false);
        u.setFailedLoginAttempts(0);
        u.setCreatedAt(LocalDateTime.now());
        return u;
    }
}
