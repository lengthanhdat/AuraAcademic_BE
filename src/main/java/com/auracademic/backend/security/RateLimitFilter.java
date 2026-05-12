package com.auracademic.backend.security;

import com.auracademic.backend.util.ClientInfoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${app.rate-limit.login.capacity:10}")
    private int capacity;

    @Value("${app.rate-limit.login.refill-minutes:1}")
    private int refillMinutes;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Chỉ rate-limit endpoint login
        if (!request.getRequestURI().equals("/api/auth/login")) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = getClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, this::createBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            System.out.println("Rate limit vượt quá giới hạn cho IP: " + ip);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                    "status", 429,
                    "error", "TOO_MANY_REQUESTS",
                    "message", "Quá nhiều yêu cầu đăng nhập. Vui lòng thử lại sau " + refillMinutes + " phút."
            )));
        }
    }

    private Bucket createBucket(String ip) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(refillMinutes))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String getClientIp(HttpServletRequest request) {
        return ClientInfoUtil.getClientIp(request);
    }
}
