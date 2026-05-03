package com.auracademic.backend.service;

import com.auracademic.backend.dto.LoginRequest;
import com.auracademic.backend.dto.RegisterRequest;
import com.auracademic.backend.dto.UserProfileResponse;
import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.RefreshTokenRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private EmailService emailService;
    @Mock private TwoFactorService twoFactorService;
    @Mock private AuditLogService auditLogService;
    @Mock private UserMapper userMapper;

    @InjectMocks private AuthService authService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** Helper — tạo User không dùng Lombok builder */
    private User newUser(String email, String rawPassword, String role, boolean emailVerified) {
        User u = new User();
        u.setId("test-id-001");
        u.setFullName("Test User");
        u.setEmail(email);
        u.setPassword(rawPassword != null ? passwordEncoder.encode(rawPassword) : null);
        u.setRole(role);
        u.setProvider("local");
        u.setEmailVerified(emailVerified);
        u.setTwoFactorEnabled(false);
        u.setAccountLocked(false);
        u.setFailedLoginAttempts(0);
        return u;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(authService, "googleClientId", "test-client-id");
        ReflectionTestUtils.setField(authService, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(authService, "lockDurationMinutes", 15);
        ReflectionTestUtils.setField(authService, "emailVerificationTtl", 1440);
        ReflectionTestUtils.setField(authService, "passwordResetTtl", 60);
        ReflectionTestUtils.setField(authService, "refreshTokenExpiry", 604800000L);
    }

    // ─── Register ─────────────────────────────────────────────────────────────

    @Test
    void register_shouldThrowIfEmailExists() {
        when(userRepository.existsByEmail("existing@test.com")).thenReturn(true);

        RegisterRequest req = new RegisterRequest();
        req.setEmail("existing@test.com");
        req.setPassword("Test1234");
        req.setFullName("Test User");

        assertThatThrownBy(() -> authService.register(req, "127.0.0.1"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Email đã được đăng ký");
    }

    @Test
    void register_shouldSaveNewUserAndSendEmail() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(emailService).sendVerificationEmail(anyString(), anyString(), anyString());
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any(), anyBoolean(), any());

        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@test.com");
        req.setPassword("Test1234");
        req.setFullName("New User");

        authService.register(req, "127.0.0.1");

        verify(userRepository).save(argThat(u -> "new@test.com".equals(u.getEmail())));
        verify(emailService).sendVerificationEmail(eq("new@test.com"), anyString(), anyString());
    }

    // ─── Login ────────────────────────────────────────────────────────────────

    @Test
    void login_shouldThrowIfUserNotFound() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any(), anyBoolean(), any());

        LoginRequest req = new LoginRequest();
        req.setEmail("notfound@test.com");
        req.setPassword("Test1234");

        assertThatThrownBy(() -> authService.login(req, "127.0.0.1", "TestAgent"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void login_shouldThrowIfEmailNotVerified() {
        User user = newUser("user@test.com", "Test1234", "student", false);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        LoginRequest req = new LoginRequest();
        req.setEmail("user@test.com");
        req.setPassword("Test1234");

        assertThatThrownBy(() -> authService.login(req, "127.0.0.1", "TestAgent"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("chưa được xác thực email");
    }

    @Test
    void login_shouldThrowIfWrongPassword() {
        User user = newUser("user@test.com", "CorrectPass1", "student", true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any(), anyBoolean(), any());

        LoginRequest req = new LoginRequest();
        req.setEmail("user@test.com");
        req.setPassword("WrongPass1");

        assertThatThrownBy(() -> authService.login(req, "127.0.0.1", "TestAgent"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void login_shouldReturnTokensOnSuccess() {
        User user = newUser("user@test.com", "Test1234", "student", true);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtTokenProvider.getAccessTokenExpiry()).thenReturn(900L);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserProfileResponse profileResponse = new UserProfileResponse();
        profileResponse.setEmail("user@test.com");
        when(userMapper.toProfile(any())).thenReturn(profileResponse);
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any(), anyBoolean(), any());

        LoginRequest req = new LoginRequest();
        req.setEmail("user@test.com");
        req.setPassword("Test1234");

        var response = authService.login(req, "127.0.0.1", "TestAgent");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getUser().getEmail()).isEqualTo("user@test.com");
    }

    // ─── Forgot Password ──────────────────────────────────────────────────────

    @Test
    void forgotPassword_shouldSendEmailIfUserExists() {
        User user = newUser("user@test.com", null, "student", true);
        user.setFullName("Test User");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        doNothing().when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyString());
        doNothing().when(auditLogService).log(any(), any(), any(), any(), any(), anyBoolean(), any());

        authService.forgotPassword("user@test.com", "127.0.0.1");

        verify(emailService).sendPasswordResetEmail(eq("user@test.com"), anyString(), anyString());
    }

    @Test
    void forgotPassword_shouldNotThrowIfUserNotExists() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        // Không throw — bảo vệ user enumeration (OWASP)
        assertThatCode(() -> authService.forgotPassword("ghost@test.com", "127.0.0.1"))
                .doesNotThrowAnyException();
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any());
    }
}
