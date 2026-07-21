package com.walmal.content.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.model.ApiResponse;
import com.walmal.content.application.HomeContentService;
import com.walmal.content.application.dto.ContentImageDto;
import com.walmal.content.domain.HomeContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * REST controller for the editable home-page CMS document.
 * Base path: {@code /api/v1/content}.
 *
 * <p>Security model:</p>
 * <ul>
 *   <li>{@code GET /home} — public (published content).</li>
 *   <li>{@code GET /home/draft} — dual-auth: a valid {@code previewToken} query param OR an
 *       ADMIN/STAFF JWT. Self-authorizes below (permitAll at the filter chain) so the
 *       token path can be reached without a JWT.</li>
 *   <li>{@code PUT /home/draft}, {@code POST /images} — ADMIN or STAFF (method security).</li>
 *   <li>{@code POST /home/publish} — ADMIN only (method security).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/content")
@Tag(name = "Home Content", description = "Editable home-page CMS document (hero, category tiles, promo) with draft/publish lifecycle")
public class ContentController {

    /**
     * The only home-page sections that own images. {@code section} flows into the
     * storage key ({@code home/{section}/...}) and, unlike {@code filename}, is not
     * sanitized downstream — so it is allow-listed here to prevent path traversal
     * (e.g. {@code ../evil}) into the object key.
     */
    private static final java.util.regex.Pattern SECTION_PATTERN =
            java.util.regex.Pattern.compile("^(hero|tile|promo)$");

    private final HomeContentService homeContentService;
    private final String previewToken;

    public ContentController(HomeContentService homeContentService,
                             @Value("${walmal.content.preview-token}") String previewToken) {
        this.homeContentService = homeContentService;
        this.previewToken = previewToken;
    }

    @Operation(summary = "Get published home content",
            description = "Returns the live, published home-page document. Public — no authentication required.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Published content returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Nothing has been published yet")
    })
    @GetMapping("/home")
    public ResponseEntity<ApiResponse<HomeContent>> getPublished() {
        Optional<HomeContent> published = homeContentService.getPublished();
        return published
                .map(content -> ResponseEntity.ok(ApiResponse.ok(content)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Get draft home content",
            description = "Returns the editor's draft document (DRAFT → PUBLISHED → DEFAULT fallback). "
                    + "Dual-auth: pass a valid previewToken query param, or authenticate as ADMIN/STAFF. "
                    + "On failure the controller throws AccessDeniedException, which the app's "
                    + "GlobalExceptionHandler renders as 403 for both the missing-token and wrong-role cases.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Draft returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "No valid preview token and not an authenticated ADMIN/STAFF")
    })
    @GetMapping("/home/draft")
    public ResponseEntity<ApiResponse<HomeContent>> getDraft(
            @RequestParam(required = false) String previewToken,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {

        // Require a non-blank configured token: an empty configured token would make
        // MessageDigest.isEqual([], []) true for an empty ?previewToken= param — an
        // auth bypass. Fail closed if the token was never configured.
        boolean tokenOk = previewToken != null && !this.previewToken.isBlank()
                && MessageDigest.isEqual(
                previewToken.getBytes(StandardCharsets.UTF_8),
                this.previewToken.getBytes(StandardCharsets.UTF_8));
        boolean roleOk = principal != null
                && ("ADMIN".equals(principal.role()) || "STAFF".equals(principal.role()));

        if (!tokenOk && !roleOk) {
            throw new AccessDeniedException("A valid preview token or ADMIN/STAFF authentication is required.");
        }
        return ResponseEntity.ok(ApiResponse.ok(homeContentService.getDraft()));
    }

    @Operation(summary = "Save draft home content",
            description = "Upserts the DRAFT document. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Draft saved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid content document"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PutMapping("/home/draft")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<Void>> saveDraft(
            @Valid @RequestBody HomeContent body,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        homeContentService.saveDraft(body, principal.username());
        return ResponseEntity.ok(ApiResponse.ok("Draft saved", null));
    }

    @Operation(summary = "Publish draft home content",
            description = "Promotes the current DRAFT to PUBLISHED. Requires ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Draft published"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "No draft to publish")
    })
    @PostMapping("/home/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> publish(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        try {
            homeContentService.publish(principal.username());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload home content image",
            description = "Uploads a home-page section image to storage and returns its reference URL. "
                    + "Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Image uploaded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing file or non-image content type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<ContentImageDto>> uploadImage(
            @RequestParam String section,
            @RequestParam MultipartFile file,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) throws IOException {

        // section is a required @RequestParam (missing → 400 before we get here), so it
        // is non-null; validate its value against the allow-list.
        if (!SECTION_PATTERN.matcher(section).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid section");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only image uploads are allowed");
        }

        ContentImageDto dto = homeContentService.uploadImage(
                section, file.getInputStream(), file.getOriginalFilename(),
                contentType, file.getSize(), principal.username());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Image uploaded", dto));
    }
}
