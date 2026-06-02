package com.auracademic.backend.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int TWO_FACTOR_OTP_TTL_MINUTES = 10;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TwoFactorService twoFactorService;
    private final AuditLogService auditLogService;
    private final UserMapper userMapper;
    private final SettingService settingService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenProvider jwtTokenProvider,
                       PasswordEncoder passwordEncoder,
                       EmailService emailService,
                       TwoFactorService twoFactorService,
                       AuditLogService auditLogService,
                       UserMapper userMapper,
                       SettingService settingService) {
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

    public void register(RegisterRequest request, String ipAddress) {
        if (settingService.getBoolean(SettingService.MAINTENANCE_MODE, false)) {
            throw new AuthException("Hệ thống đang bảo trì, vui lòng quay lại sau.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AuthException("Email đã được đăng ký");
        }

        User user = buildLocalUser(request.getFullName(), request.getEmail(),
                passwordEncoder.encode(request.getPassword()), request.getRole());

        if (settingService.getBoolean(SettingService.REQUIRE_EMAIL_VERIFY, true)) {
            String verificationToken = String.format("%06d", new java.util.Random().nextInt(1000000));
            user.setEmailVerificationToken(verificationToken);
            user.setEmailVerificationExpiry(LocalDateTime.now().plusMinutes(emailVerificationTtl));
            userRepository.save(user);
            emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), verificationToken);
        } else {
            user.setEmailVerified(true);
            user.setEmailVerificationToken(null);
            user.setEmailVerificationExpiry(null);
            userRepository.save(user);
        }

        auditLogService.log(user.getId(), user.getEmail(), "REGISTER", ipAddress, null, true, null);
        log.info("Người dùng mới đã đăng ký: {}", user.getEmail());
    }

    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    auditLogService.log(null, request.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "User not found");
                    return new AuthException("Email hoặc mật khẩu không chính xác");
                });

        checkAccountLock(user);
        checkAccessAllowedBySettings(user);

        boolean passwordOk = verifyPassword(request.getPassword(), user);
        if (!passwordOk) {
            handleFailedLogin(user, ipAddress, userAgent);
            throw new AuthException("Email hoặc mật khẩu không chính xác");
        }

        if (user.isTwoFactorEnabled()) {
            if (request.getTwoFactorCode() == null || request.getTwoFactorCode().isBlank()) {
                sendLoginTwoFactorOtp(user);
                throw new TwoFactorRequiredException("Ma OTP 2FA da duoc gui toi email cua ban");
            }
            if (!verifyLoginTwoFactorOtp(user, request.getTwoFactorCode())) {
                auditLogService.log(user.getId(), user.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "Invalid 2FA code");
                throw new AuthException("Ma xac thuc 2FA khong dung hoac da het han");
            }
            user.setTwoFactorSecret(null);
            user.setTwoFactorExpiry(null);
        }

        if (false && user.isTwoFactorEnabled()) {
            if (request.getTwoFactorCode() == null || request.getTwoFactorCode().isBlank()) {
                throw new TwoFactorRequiredException("Vui lòng nhập mã xác thực 2FA");
            }
            if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), request.getTwoFactorCode())) {
                auditLogService.log(user.getId(), user.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "Invalid 2FA code");
                throw new AuthException("Mã xác thực 2FA không đúng");
            }
        }

        notifySuspiciousLoginIfNeeded(user, ipAddress, userAgent);

        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        auditLogService.log(user.getId(), user.getEmail(), "LOGIN", ipAddress, userAgent, true, null);
        return buildAuthResponse(user, ipAddress, userAgent);
    }

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
        checkAccessAllowedBySettings(user);

        refreshTokenRepository.delete(refreshToken);
        return buildAuthResponse(user, ipAddress, userAgent);
    }

    public void logout(String refreshTokenStr, String userId, String ipAddress) {
        refreshTokenRepository.findByToken(refreshTokenStr)
                .ifPresent(refreshTokenRepository::delete);
        auditLogService.log(userId, null, "LOGOUT", ipAddress, null, true, null);
    }

    public void verifyEmail(String email, String token) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("Email không tồn tại"));

        if (user.isEmailVerified()) {
            return;
        }
        if (!settingService.getBoolean(SettingService.REQUIRE_EMAIL_VERIFY, true)) {
            user.setEmailVerified(true);
            user.setEmailVerificationToken(null);
            user.setEmailVerificationExpiry(null);
            userRepository.save(user);
            return;
        }
        if (token == null || !token.equals(user.getEmailVerificationToken())) {
            throw new AuthException("Mã xác thực không hợp lệ");
        }
        if (user.getEmailVerificationExpiry() == null
                || user.getEmailVerificationExpiry().isBefore(LocalDateTime.now())) {
            throw new AuthException("Token xác thực đã hết hạn. Vui lòng yêu cầu gửi lại email.");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiry(null);
        userRepository.save(user);
        log.info("Email xác thực thành công: {}", user.getEmail());
    }

    public void resendVerification(String email) {
        if (!settingService.getBoolean(SettingService.REQUIRE_EMAIL_VERIFY, true)) {
            throw new AuthException("Xác thực email đang được tắt.");
        }

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

    public void forgotPassword(String email, String ipAddress) {
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
        refreshTokenRepository.deleteByUserId(user.getId());
        userRepository.save(user);

        auditLogService.log(user.getId(), user.getEmail(), "PASSWORD_RESET", ipAddress, null, true, null);
        log.info("Mật khẩu đã được đặt lại cho: {}", user.getEmail());
    }

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

            User user = userRepository.findByProviderAndProviderId("google", googleId)
                    .orElseGet(() -> userRepository.findByEmail(email)
                            .map(existingUser -> {
                                existingUser.setProvider("google");
                                existingUser.setProviderId(googleId);
                                existingUser.setEmailVerified(true);
                                return userRepository.save(existingUser);
                            })
                            .orElseGet(() -> userRepository.save(
                                    buildOAuth2User(name, email, "google", googleId, pictureUrl))));

            checkAccessAllowedBySettings(user);
            notifySuspiciousLoginIfNeeded(user, ipAddress, userAgent);

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

    private void checkAccessAllowedBySettings(User user) {
        if (settingService.getBoolean(SettingService.MAINTENANCE_MODE, false)
                && !"admin".equalsIgnoreCase(user.getRole())) {
            throw new AuthException("Hệ thống đang bảo trì, vui lòng quay lại sau.");
        }
        if (settingService.getBoolean(SettingService.REQUIRE_EMAIL_VERIFY, true)
                && !user.isEmailVerified()) {
            throw new AuthException("Tài khoản chưa được xác thực email. Vui lòng kiểm tra hộp thư của bạn.");
        }
    }

    private void checkAccountLock(User user) {
        if ("admin".equalsIgnoreCase(user.getRole())) return;

        if (user.isAccountLocked()) {
            if (user.getLockExpiry() != null && user.getLockExpiry().isBefore(LocalDateTime.now())) {
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
        if (rawPassword.equals(stored)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    private void handleFailedLogin(User user, String ipAddress, String userAgent) {
        boolean enforceLock = settingService.getBoolean(SettingService.LOCK_AFTER_5_FAILS, true);
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        boolean isAdmin = "admin".equalsIgnoreCase(user.getRole());
        if (enforceLock && attempts >= maxFailedAttempts && !isAdmin) {
            user.setAccountLocked(true);
            user.setLockExpiry(LocalDateTime.now().plusMinutes(lockDurationMinutes));
            auditLogService.log(user.getId(), user.getEmail(), "ACCOUNT_LOCKED", ipAddress, userAgent, false,
                    "Locked after " + attempts + " failed attempts");
        }

        userRepository.save(user);
        auditLogService.log(user.getId(), user.getEmail(), "FAILED_LOGIN", ipAddress, userAgent, false, "Invalid password");
    }

    private void notifySuspiciousLoginIfNeeded(User user, String ipAddress, String userAgent) {
        if (!settingService.getBoolean(SettingService.ALERT_SUSPICIOUS_LOGIN, false)) {
            return;
        }

        var activeSessions = refreshTokenRepository.findByUserId(user.getId()).stream()
                .filter(token -> !token.isExpired())
                .toList();

        boolean hasPreviousActiveSession = !activeSessions.isEmpty();
        boolean knownContext = activeSessions.stream().anyMatch(token ->
                Objects.equals(normalize(token.getIpAddress()), normalize(ipAddress))
                        && Objects.equals(normalize(token.getDeviceInfo()), normalize(userAgent)));

        if (hasPreviousActiveSession && !knownContext) {
            emailService.sendSecurityAlertEmail(user.getEmail(), user.getFullName(), ipAddress, userAgent, LocalDateTime.now());
            auditLogService.log(user.getId(), user.getEmail(), "SUSPICIOUS_LOGIN_ALERT", ipAddress, userAgent, true,
                    "New login context detected");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private AuthResponse buildAuthResponse(User user, String ipAddress, String userAgent) {
        if (settingService.getBoolean(SettingService.PREVENT_CONCURRENT_LOGIN, false)) {
            refreshTokenRepository.deleteByUserId(user.getId());
        }

        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(user, sessionId);
        String refreshTokenStr = jwtTokenProvider.generateRefreshToken(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setToken(refreshTokenStr);
        refreshToken.setSessionId(sessionId);
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
        authResponse.setTwoFactorRequired(settingService.getBoolean(SettingService.REQUIRE_2FA, false)
                && !user.isTwoFactorEnabled()
                && !"admin".equalsIgnoreCase(user.getRole()));
        return authResponse;
    }

    public UserProfileResponse mapToProfile(User user) {
        return userMapper.toProfile(user);
    }

    private void sendLoginTwoFactorOtp(User user) {
        String otp = String.format("%06d", new java.util.Random().nextInt(1000000));
        user.setTwoFactorSecret(otp);
        user.setTwoFactorExpiry(LocalDateTime.now().plusMinutes(TWO_FACTOR_OTP_TTL_MINUTES));
        userRepository.save(user);
        emailService.sendTwoFactorOtpEmail(user.getEmail(), user.getFullName(), otp, TWO_FACTOR_OTP_TTL_MINUTES);
    }

    private boolean verifyLoginTwoFactorOtp(User user, String code) {
        if (user.getTwoFactorSecret() == null || user.getTwoFactorExpiry() == null) {
            return false;
        }
        if (user.getTwoFactorExpiry().isBefore(LocalDateTime.now())) {
            user.setTwoFactorSecret(null);
            user.setTwoFactorExpiry(null);
            userRepository.save(user);
            return false;
        }
        return user.getTwoFactorSecret().equals(code.trim());
    }

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
