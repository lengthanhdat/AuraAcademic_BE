package com.auracademic.backend.security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auracademic.backend.model.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user) {
        return generateAccessToken(user, null);
    }

    public String generateAccessToken(User user, String sessionId) {
        JwtBuilder builder = Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole())
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry));

        if (sessionId != null && !sessionId.isBlank()) {
            builder.claim("sessionId", sessionId);
        }

        return builder.signWith(getSigningKey()).compact();
    }

    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .subject(user.getId())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUserId(String token) {
        return extractClaims(token).getSubject();
    }

    public Optional<String> extractSessionId(String token) {
        Object sessionId = extractClaims(token).get("sessionId");
        if (sessionId == null || String.valueOf(sessionId).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(sessionId));
    }

    public boolean validateToken(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT token đã hết hạn: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token không được hỗ trợ: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT token không hợp lệ: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("JWT signature không hợp lệ: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims trống: {}", e.getMessage());
        }
        return false;
    }

    public long getAccessTokenExpiry() {
        return accessTokenExpiry / 1000; // trả về seconds
    }
}
