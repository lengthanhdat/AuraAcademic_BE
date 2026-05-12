package com.auracademic.backend.service;

import com.auracademic.backend.dto.*;
import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.RefreshTokenRepository;
import com.auracademic.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final UserMapper userMapper;
    private final SettingService settingService;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       AuditLogService auditLogService,
                       UserMapper userMapper,
                       SettingService settingService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.userMapper = userMapper;
        this.settingService = settingService;
    }

    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));
        return userMapper.toProfile(user);
    }

    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

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
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        user.setAvatarUrl(base64Avatar);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return userMapper.toProfile(user);
    }

    public void changePassword(String userId, ChangePasswordRequest request, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new AuthException("Mật khẩu hiện tại không đúng");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        refreshTokenRepository.deleteByUserId(userId);
        userRepository.save(user);

        auditLogService.log(userId, user.getEmail(), "PASSWORD_CHANGE", ipAddress, null, true, null);
    }

    public TwoFactorSetupResponse setup2fa(String userId, TwoFactorService twoFactorService) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        String secret = twoFactorService.generateSecret();
        user.setTwoFactorSecret(secret);
        userRepository.save(user);

        TwoFactorSetupResponse tfa = new TwoFactorSetupResponse();
        tfa.setSecret(secret);
        tfa.setQrCodeUri(twoFactorService.generateQrCodeUri(user.getEmail(), secret));
        tfa.setQrCodeImage(twoFactorService.generateQrCodeImage(user.getEmail(), secret));
        return tfa;
    }

    public void enable2fa(String userId, String code, TwoFactorService twoFactorService, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        if (user.getTwoFactorSecret() == null) {
            throw new AuthException("Chưa khởi tạo 2FA. Vui lòng thực hiện /2fa/setup trước.");
        }
        if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), code)) {
            throw new AuthException("Mã xác thực không đúng");
        }

        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        auditLogService.log(userId, user.getEmail(), "2FA_ENABLE", ipAddress, null, true, null);
    }

    public void disable2fa(String userId, String code, TwoFactorService twoFactorService, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("Không tìm thấy người dùng"));

        if (!user.isTwoFactorEnabled()) {
            throw new AuthException("2FA chưa được bật");
        }
        if (settingService.getBoolean(SettingService.REQUIRE_2FA, false)
                && !"admin".equalsIgnoreCase(user.getRole())) {
            throw new AuthException("Hệ thống đang bắt buộc 2FA, không thể tắt chức năng này.");
        }
        if (!twoFactorService.verifyCode(user.getTwoFactorSecret(), code)) {
            throw new AuthException("Mã xác thực không đúng");
        }

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        auditLogService.log(userId, user.getEmail(), "2FA_DISABLE", ipAddress, null, true, null);
    }
}
