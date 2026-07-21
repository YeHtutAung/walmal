package com.walmal.content.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.content.application.HomeContentService;
import com.walmal.content.application.dto.ContentImageDto;
import com.walmal.content.domain.CategoryTile;
import com.walmal.content.domain.Cta;
import com.walmal.content.domain.Hero;
import com.walmal.content.domain.HomeContent;
import com.walmal.content.domain.Promo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link ContentController}.
 *
 * <p>Imports {@code AuthSecurityConfig} so the full security filter chain (JWT validation,
 * role checks, permitAll on the two public GET paths) is exercised. The service is mocked.</p>
 */
@WebMvcTest(controllers = ContentController.class)
@Import(AuthSecurityConfig.class)
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15",
        "walmal.content.preview-token=test-preview"
})
class ContentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean HomeContentService homeContentService;
    @MockitoBean TokenValidationService tokenValidationService;

    private static HomeContent validContent() {
        return new HomeContent(
                new Hero("New", "Welcome to walmal", "Great deals",
                        new Cta("Shop now", "/shop"), null, "/img/hero.png"),
                List.of(new CategoryTile("Electronics", "/c/electronics", "/img/e.png")),
                new Promo("Limited", "Summer Sale", "Up to 50% off",
                        new Cta("See offers", "/sale"), "/img/promo.png"));
    }

    // ── GET /home ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200WithPublished_when_publishedExists")
    void should_return200WithPublished_when_publishedExists() throws Exception {
        when(homeContentService.getPublished()).thenReturn(Optional.of(validContent()));

        mockMvc.perform(get("/api/v1/content/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hero.headline").value("Welcome to walmal"));
    }

    @Test
    @DisplayName("should_return204_when_nothingPublished")
    void should_return204_when_nothingPublished() throws Exception {
        when(homeContentService.getPublished()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/content/home"))
                .andExpect(status().isNoContent());
    }

    // ── GET /home/draft ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200Draft_when_validPreviewTokenAndNoAuth")
    void should_return200Draft_when_validPreviewTokenAndNoAuth() throws Exception {
        when(homeContentService.getDraft()).thenReturn(validContent());

        mockMvc.perform(get("/api/v1/content/home/draft").param("previewToken", "test-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hero.headline").value("Welcome to walmal"));
    }

    @Test
    @DisplayName("should_denyDraft_when_wrongTokenAndNoAuth")
    void should_denyDraft_when_wrongTokenAndNoAuth() throws Exception {
        // Anonymous request with a wrong token: the controller throws AccessDeniedException.
        // For an anonymous principal Spring Security's ExceptionTranslationFilter invokes the
        // authenticationEntryPoint (401); an authenticated-but-forbidden principal would get 403.
        // Assert either to stay robust to that distinction.
        mockMvc.perform(get("/api/v1/content/home/draft").param("previewToken", "nope"))
                .andExpect(status().is(anyOf401or403()));
    }

    @Test
    @DisplayName("should_return200Draft_when_adminNoToken")
    void should_return200Draft_when_adminNoToken() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        when(homeContentService.getDraft()).thenReturn(validContent());

        mockMvc.perform(get("/api/v1/content/home/draft")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promo.heading").value("Summer Sale"));
    }

    // ── PUT /home/draft ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return400_when_saveDraftWithInvalidBody")
    void should_return400_when_saveDraftWithInvalidBody() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");
        // Blank headline violates @NotBlank on Hero.headline.
        HomeContent invalid = new HomeContent(
                new Hero("New", "  ", "Great deals",
                        new Cta("Shop now", "/shop"), null, "/img/hero.png"),
                List.of(),
                new Promo("Limited", "Summer Sale", "Up to 50% off",
                        new Cta("See offers", "/sale"), "/img/promo.png"));

        mockMvc.perform(put("/api/v1/content/home/draft")
                        .with(authentication(buildAuth(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return400_when_saveDraftWithNonRelativeHref")
    void should_return400_when_saveDraftWithNonRelativeHref() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");
        // href not starting with '/' violates Cta.href @Pattern.
        HomeContent invalid = new HomeContent(
                new Hero("New", "Welcome to walmal", "Great deals",
                        new Cta("Shop now", "https://evil.example.com"), null, "/img/hero.png"),
                List.of(),
                new Promo("Limited", "Summer Sale", "Up to 50% off",
                        new Cta("See offers", "/sale"), "/img/promo.png"));

        mockMvc.perform(put("/api/v1/content/home/draft")
                        .with(authentication(buildAuth(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return200_when_saveDraftValidByStaff")
    void should_return200_when_saveDraftValidByStaff() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");

        mockMvc.perform(put("/api/v1/content/home/draft")
                        .with(authentication(buildAuth(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validContent())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── POST /home/publish ────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return204_when_publishByAdmin")
    void should_return204_when_publishByAdmin() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        mockMvc.perform(post("/api/v1/content/home/publish")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("should_return403_when_publishByStaff")
    @WithMockUser(username = "staff1", roles = "STAFF")
    void should_return403_when_publishByStaff() throws Exception {
        mockMvc.perform(post("/api/v1/content/home/publish"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return409_when_publishWithNoDraft")
    void should_return409_when_publishWithNoDraft() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        doThrow(new IllegalStateException("No draft to publish"))
                .when(homeContentService).publish(anyString());

        mockMvc.perform(post("/api/v1/content/home/publish")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isConflict());
    }

    // ── POST /images ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return201WithImageUrl_when_uploadPngByStaff")
    void should_return201WithImageUrl_when_uploadPngByStaff() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");
        MockMultipartFile file = new MockMultipartFile(
                "file", "hero.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3, 4});

        when(homeContentService.uploadImage(eq("hero"), any(), anyString(), anyString(),
                anyLong(), anyString()))
                .thenReturn(new ContentImageDto("http://minio.local/content/hero.png"));

        mockMvc.perform(multipart("/api/v1/content/images")
                        .file(file)
                        .param("section", "hero")
                        .with(authentication(buildAuth(staff))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageUrl").value("http://minio.local/content/hero.png"));
    }

    @Test
    @DisplayName("should_return400_when_uploadWithTraversalSection")
    void should_return400_when_uploadWithTraversalSection() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");
        // A valid PNG, but a path-traversal section value must be rejected before it
        // reaches the storage key builder.
        MockMultipartFile file = new MockMultipartFile(
                "file", "hero.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/v1/content/images")
                        .file(file)
                        .param("section", "../evil")
                        .with(authentication(buildAuth(staff))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return400_when_uploadNonImageContentType")
    void should_return400_when_uploadNonImageContentType() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(UUID.randomUUID(), "staff1", "STAFF");
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());

        mockMvc.perform(multipart("/api/v1/content/images")
                        .file(file)
                        .param("section", "hero")
                        .with(authentication(buildAuth(staff))))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }

    private static org.hamcrest.Matcher<Integer> anyOf401or403() {
        return org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(401),
                org.hamcrest.Matchers.is(403));
    }
}
