package com.auracademic.backend.service;

import com.auracademic.backend.dto.ChangePasswordRequest;
import com.auracademic.backend.dto.TwoFactorSetupResponse;
import com.auracademic.backend.dto.UpdateProfileRequest;
import com.auracademic.backend.dto.UserProfileResponse;
import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.RefreshTokenRepository;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {

    private static final int TWO_FACTOR_OTP_TTL_MINUTES = 10;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserMapper userMapper;
    private final SettingService settingService;
    private final EmailService emailService;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       AuditLogService auditLogService,
                       UserMapper userMapper,
                       SettingService settingService,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.userMapper = userMapper;
        this.settingService = settingService;
        this.emailService = emailService;
    }

    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));
        return userMapper.toProfile(user);
    }

    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName());
        }
        if (request.getStudentId() != null) user.setStudentId(request.getStudentId());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber());
        if (request.getBirthDate() != null) user.setBirthDate(request.getBirthDate());
        if (request.getGender() != null) user.setGender(request.getGender());
        if (request.getTitle() != null) user.setTitle(request.getTitle());
        if (request.getDepartment() != null) user.setDepartment(request.getDepartment());
        if (request.getWorkplace() != null) user.setWorkplace(request.getWorkplace());
        if (request.getSchedule() != null) user.setSchedule(request.getSchedule());
        if (request.getBio() != null) user.setBio(request.getBio());
        if (request.getCertificates() != null) user.setCertificates(request.getCertificates());
        if (request.getExperience() != null) user.setExperience(request.getExperience());

        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return userMapper.toProfile(user);
    }

    public UserProfileResponse updateAvatar(String userId, String base64Avatar) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        user.setAvatarUrl(base64Avatar);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return userMapper.toProfile(user);
    }

    public void changePassword(String userId, ChangePasswordRequest request, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        boolean hasExistingPassword = user.getPassword() != null && !user.getPassword().isBlank();
        if (hasExistingPassword) {
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new AuthException("Vui lòng nhập mật khẩu hiện tại");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new AuthException("Mật khẩu hiện tại không đúng");
            }
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        refreshTokenRepository.deleteByUserId(userId);
        userRepository.save(user);

        auditLogService.log(userId, user.getEmail(), "PASSWORD_CHANGE", ipAddress, null, true, null);
    }

    public TwoFactorSetupResponse setup2fa(String userId, TwoFactorService twoFactorService) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        if (!user.isEmailVerified()) {
            throw new AuthException("Vui long xac minh email truoc khi bat 2FA.");
        }

        String otp = generateOtp();
        user.setTwoFactorSecret(otp);
        user.setTwoFactorExpiry(LocalDateTime.now().plusMinutes(TWO_FACTOR_OTP_TTL_MINUTES));
        userRepository.save(user);
        emailService.sendTwoFactorOtpEmail(user.getEmail(), user.getFullName(), otp, TWO_FACTOR_OTP_TTL_MINUTES);

        TwoFactorSetupResponse response = new TwoFactorSetupResponse();
        response.setSecret(null);
        response.setQrCodeUri(null);
        response.setQrCodeImage(null);
        return response;
    }

    public void enable2fa(String userId, String code, TwoFactorService twoFactorService, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        verifyEmailOtp(user, code);

        user.setTwoFactorEnabled(true);
        user.setTwoFactorSecret(null);
        user.setTwoFactorExpiry(null);
        userRepository.save(user);
        auditLogService.log(userId, user.getEmail(), "2FA_ENABLE", ipAddress, null, true, null);
    }

    public void disable2fa(String userId, String code, TwoFactorService twoFactorService, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Khong tim thay nguoi dung"));

        if (!user.isTwoFactorEnabled()) {
            throw new AuthException("2FA chua duoc bat");
        }
        if (settingService.getBoolean(SettingService.REQUIRE_2FA, false)
                && !"admin".equalsIgnoreCase(user.getRole())) {
            throw new AuthException("He thong dang bat buoc 2FA, khong the tat chuc nang nay.");
        }

        verifyEmailOtp(user, code);

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.setTwoFactorExpiry(null);
        userRepository.save(user);
        auditLogService.log(userId, user.getEmail(), "2FA_DISABLE", ipAddress, null, true, null);
    }

    private void verifyEmailOtp(User user, String code) {
        if (user.getTwoFactorSecret() == null || user.getTwoFactorExpiry() == null) {
            throw new AuthException("Chua gui ma OTP 2FA. Vui long yeu cau gui ma truoc.");
        }
        if (user.getTwoFactorExpiry().isBefore(LocalDateTime.now())) {
            user.setTwoFactorSecret(null);
            user.setTwoFactorExpiry(null);
            userRepository.save(user);
            throw new AuthException("Ma OTP da het han. Vui long gui ma moi.");
        }
        if (code == null || !user.getTwoFactorSecret().equals(code.trim())) {
            throw new AuthException("Ma xac thuc khong dung");
        }
    }

    private String generateOtp() {
        return String.format("%06d", new java.util.Random().nextInt(1000000));
    }
}
