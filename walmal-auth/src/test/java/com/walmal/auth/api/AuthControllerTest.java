package com.walmal.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.api.dto.CreateUserRequest;
import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RefreshTokenRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.api.dto.UserProfileResponse;
import com.walmal.auth.application.AuthService;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtAuthenticationFilter;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for AuthController.
 * Security filter chain is loaded; mocked TokenValidationService controls JWT behaviour.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({AuthSecurityConfig.class, AuthExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenValidationService tokenValidationService;

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200WithTokens_when_loginCredentialsAreValid")
    void should_return200WithTokens_when_loginCredentialsAreValid() throws Exception {
        TokenResponse tokens = TokenResponse.bearer("access.token.here", "userId:tokenId", 900, "CUSTOMER");
        when(authService.login(any(LoginRequest.class))).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("alice", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.token.here"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    @DisplayName("should_return401_when_loginCredentialsAreInvalid")
    void should_return401_when_loginCredentialsAreInvalid() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BusinessRuleException("Invalid credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("alice", "wrongpassword"))))
                .andExpect(status().isUnauthorized());
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return201WithTokens_when_registrationDataIsValid")
    void should_return201WithTokens_when_registrationDataIsValid() throws Exception {
        TokenResponse tokens = TokenResponse.bearer("access.token", "userId:tokenId", 900, "CUSTOMER");
        when(authService.register(any(RegisterRequest.class))).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("bob", "bob@example.com", "password123", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("should_return409_when_usernameAlreadyExists")
    void should_return409_when_usernameAlreadyExists() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new BusinessRuleException("Username already taken: bob"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("bob", "bob@example.com", "password123", null))))
                .andExpect(status().isConflict());
    }

    // ── /me ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return401_when_getMeCalledWithoutAuthentication")
    void should_return401_when_getMeCalledWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return200WithProfile_when_getMeCalledWithValidToken")
    void should_return200WithProfile_when_getMeCalledWithValidToken() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(userId, "alice", "CUSTOMER");

        UserProfileResponse profile = new UserProfileResponse(userId, "alice", "alice@example.com", "CUSTOMER", true);
        when(authService.getCurrentUser(userId)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(authentication(buildAuth(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    // ── Deactivate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_deactivateCalledByNonAdmin")
    void should_return403_when_deactivateCalledByNonAdmin() throws Exception {
        UUID targetId = UUID.randomUUID();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");

        mockMvc.perform(post("/api/v1/auth/users/{id}/deactivate", targetId)
                        .with(authentication(buildAuth(principal))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return204_when_adminDeactivatesUser")
    void should_return204_when_adminDeactivatesUser() throws Exception {
        UUID targetId = UUID.randomUUID();
        AuthenticatedPrincipal adminPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");

        doNothing().when(authService).deactivateUser(any(UUID.class), anyString());

        mockMvc.perform(post("/api/v1/auth/users/{id}/deactivate", targetId)
                        .with(authentication(buildAuth(adminPrincipal))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("should_return401_when_deactivateCalledWithoutAuthentication")
    void should_return401_when_deactivateCalledWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users/{id}/deactivate", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ── Create user (admin-only) ──────────────────────────────────────────────

    @Test
    @DisplayName("should_return201_when_adminCreatesUser")
    void should_return201_when_adminCreatesUser() throws Exception {
        UUID newUserId = UUID.randomUUID();
        AuthenticatedPrincipal adminPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        UserProfileResponse profile = new UserProfileResponse(newUserId, "staff1", "staff1@walmal.com", "STAFF", true);

        when(authService.createUser(any(CreateUserRequest.class), anyString())).thenReturn(profile);

        mockMvc.perform(post("/api/v1/auth/users")
                        .with(authentication(buildAuth(adminPrincipal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "STAFF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("staff1"))
                .andExpect(jsonPath("$.role").value("STAFF"));
    }

    @Test
    @DisplayName("should_return403_when_nonAdminCreatesUser")
    void should_return403_when_nonAdminCreatesUser() throws Exception {
        AuthenticatedPrincipal customerPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), "customer1", "CUSTOMER");

        mockMvc.perform(post("/api/v1/auth/users")
                        .with(authentication(buildAuth(customerPrincipal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "STAFF"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return401_when_unauthenticatedCreatesUser")
    void should_return401_when_unauthenticatedCreatesUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "STAFF"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return409_when_adminCreatesUserWithDuplicateUsername")
    void should_return409_when_adminCreatesUserWithDuplicateUsername() throws Exception {
        AuthenticatedPrincipal adminPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");

        when(authService.createUser(any(CreateUserRequest.class), anyString()))
                .thenThrow(new BusinessRuleException("Username already taken: staff1"));

        mockMvc.perform(post("/api/v1/auth/users")
                        .with(authentication(buildAuth(adminPrincipal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "STAFF"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("should_return400_when_adminCreatesUserWithInvalidRole")
    void should_return400_when_adminCreatesUserWithInvalidRole() throws Exception {
        AuthenticatedPrincipal adminPrincipal = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");

        when(authService.createUser(any(CreateUserRequest.class), anyString()))
                .thenThrow(new BusinessRuleException("Invalid role: SUPERADMIN"));

        mockMvc.perform(post("/api/v1/auth/users")
                        .with(authentication(buildAuth(adminPrincipal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequest("staff1", "staff1@walmal.com", "password123", "SUPERADMIN"))))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private org.springframework.security.authentication.UsernamePasswordAuthenticationToken buildAuth(
            AuthenticatedPrincipal principal) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + principal.role())));
    }
}
