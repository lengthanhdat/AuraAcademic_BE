package com.auracademic.backend.controller;

import com.auracademic.backend.dto.RegisterRequest;
import com.auracademic.backend.exception.AuthException;
import com.auracademic.backend.exception.GlobalExceptionHandler;
import com.auracademic.backend.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@Import(GlobalExceptionHandler.class)
@org.springframework.test.context.ActiveProfiles("test")
@org.junit.jupiter.api.Disabled("Skipped per user request")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private AuthService authService;
    @MockBean  private com.auracademic.backend.security.JwtTokenProvider jwtTokenProvider;
    @MockBean  private com.auracademic.backend.security.JwtAuthFilter jwtAuthFilter;
    @MockBean  private com.auracademic.backend.security.RateLimitFilter rateLimitFilter;

    // ─── Register ─────────────────────────────────────────────────────────────

    @Test
    void register_shouldReturn201WhenValid() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Test User");
        req.setEmail("test@auracademic.vn");
        req.setPassword("Test1234");
        req.setRole("student");
        doNothing().when(authService).register(any(), anyString());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void register_shouldReturn400WhenEmailInvalid() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Test");
        req.setEmail("not-an-email");
        req.setPassword("Test1234");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void register_shouldReturn400WhenPasswordTooWeak() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Test");
        req.setEmail("test@test.com");
        req.setPassword("weak");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_shouldReturn401WhenEmailExists() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Test");
        req.setEmail("existing@test.com");
        req.setPassword("Test1234");
        req.setRole("student");
        doThrow(new AuthException("Email đã được đăng ký")).when(authService).register(any(), anyString());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_shouldReturn400WhenBodyEmpty() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPassword_shouldAlwaysReturn200() throws Exception {
        doNothing().when(authService).forgotPassword(anyString(), anyString());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anyone@test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
