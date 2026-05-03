package com.auracademic.backend.security;

import com.auracademic.backend.model.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    // Base64-encoded 256-bit secret for testing
    private static final String TEST_SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci1hdXJhLWFjYWRlbWljLXRlc3Rpbmctb25seS0yMDI2";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenExpiry", 900000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenExpiry", 604800000L);
    }

    private User buildTestUser() {
        User u = new User();
        u.setId("test-user-id-123");
        u.setEmail("test@auracademic.vn");
        u.setRole("student");
        u.setProvider("local");
        return u;
    }

    @Test
    void generateAccessToken_shouldReturnValidToken() {
        String token = jwtTokenProvider.generateAccessToken(buildTestUser());
        assertThat(token).isNotBlank();
    }

    @Test
    void generateRefreshToken_shouldReturnValidToken() {
        String token = jwtTokenProvider.generateRefreshToken(buildTestUser());
        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_shouldReturnTrueForValidToken() {
        String token = jwtTokenProvider.generateAccessToken(buildTestUser());
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_shouldReturnFalseForInvalidToken() {
        assertThat(jwtTokenProvider.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    void extractUserId_shouldReturnCorrectId() {
        String token = jwtTokenProvider.generateAccessToken(buildTestUser());
        assertThat(jwtTokenProvider.extractUserId(token)).isEqualTo("test-user-id-123");
    }

    @Test
    void extractClaims_shouldContainEmailAndRole() {
        String token = jwtTokenProvider.generateAccessToken(buildTestUser());
        Claims claims = jwtTokenProvider.extractClaims(token);
        assertThat(claims.get("email", String.class)).isEqualTo("test@auracademic.vn");
        assertThat(claims.get("role", String.class)).isEqualTo("student");
    }

    @Test
    void validateToken_shouldReturnFalseForExpiredToken() throws Exception {
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenExpiry", 1L);
        String token = jwtTokenProvider.generateAccessToken(buildTestUser());
        Thread.sleep(10);
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }
}
