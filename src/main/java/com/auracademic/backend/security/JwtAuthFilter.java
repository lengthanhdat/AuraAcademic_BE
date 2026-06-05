package com.auracademic.backend.security;

import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.RefreshTokenRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.service.SettingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SettingService settingService;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository,
                         RefreshTokenRepository refreshTokenRepository, SettingService settingService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.settingService = settingService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                String userId = jwtTokenProvider.extractUserId(token);
                User user = userRepository.findById(userId).orElse(null);
                if (user != null && user.isAccountLocked()) {
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                            "{\"error\":\"ACCOUNT_LOCKED\",\"message\":\"Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên.\"}");
                    return;
                }
                if (user != null) {
                    if (settingService.getBoolean(SettingService.PREVENT_CONCURRENT_LOGIN, false)
                            && !isCurrentSessionToken(userId, token)) {
                        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                "{\"error\":\"SESSION_REVOKED\",\"message\":\"Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.\"}");
                        return;
                    }

                    UserPrincipal principal = UserPrincipal.from(user);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (requiresTwoFactorSetup(user, request.getRequestURI())) {
                        writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                "{\"error\":\"2FA_REQUIRED\",\"message\":\"Hệ thống yêu cầu bật xác thực 2 bước trước khi tiếp tục.\"}");
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("Không thể xác thực JWT token: {}", e.getMessage());
            }
        }

        if (settingService.getBoolean(SettingService.MAINTENANCE_MODE, false)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            boolean isAdmin = auth != null && auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equalsIgnoreCase("ROLE_ADMIN"));

            String path = request.getRequestURI();
            if (!isAdmin && !path.startsWith("/api/auth/") && !isMaintenanceStatusPath(path)) {
                writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "{\"error\":\"MAINTENANCE_MODE\",\"message\":\"Hệ thống đang bảo trì\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        String queryToken = request.getParameter("token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }

    private boolean isCurrentSessionToken(String userId, String token) {
        return jwtTokenProvider.extractSessionId(token)
                .map(sessionId -> refreshTokenRepository.existsByUserIdAndSessionId(userId, sessionId))
                .orElse(false);
    }

    private boolean requiresTwoFactorSetup(User user, String path) {
        if (!settingService.getBoolean(SettingService.REQUIRE_2FA, false)) {
            return false;
        }
        if (user.isTwoFactorEnabled() || "admin".equalsIgnoreCase(user.getRole())) {
            return false;
        }
        return !(path.equals("/api/users/me") || path.startsWith("/api/users/me/2fa"));
    }

    private boolean isMaintenanceStatusPath(String path) {
        return path.equals("/api/health/status") || path.equals("/actuator/health");
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
