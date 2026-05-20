package com.walmal.product.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.model.ApiResponse;
import com.walmal.product.api.dto.response.ImageResponse;
import com.walmal.product.application.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for product image management.
 * Base path: {@code /api/v1/product}
 */
@RestController
@RequestMapping("/api/v1/product")
@Tag(name = "Product Images", description = "Upload, delete, and manage product images stored in MinIO")
public class ProductImageController {

    private final ProductImageService imageService;

    public ProductImageController(ProductImageService imageService) {
        this.imageService = imageService;
    }

    @Operation(summary = "Upload product image",
            description = "Uploads an image to MinIO and associates it with a product or variant. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Image uploaded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request or unsupported media type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product or variant not found")
    })
    @PostMapping(value = "/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<ImageResponse>> uploadImage(
            @PathVariable UUID productId,
            @RequestParam(required = false) UUID variantId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false, defaultValue = "") String altText,
            @RequestParam(required = false, defaultValue = "false") boolean isPrimary,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) throws IOException {

        ImageResponse response = imageService.uploadImage(
                productId, variantId,
                file.getInputStream(), file.getOriginalFilename(),
                file.getContentType(), file.getSize(),
                altText, isPrimary, principal.username());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Image uploaded", response));
    }

    @Operation(summary = "List product images",
            description = "Returns all images for a product, ordered by display_order")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Images returned")
    @GetMapping("/{productId}/images")
    public ResponseEntity<ApiResponse<List<ImageResponse>>> listImages(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.ok(imageService.listImages(productId)));
    }

    @Operation(summary = "Delete product image",
            description = "Deletes an image from MinIO and the database. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Image deleted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Image not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<Void> deleteImage(
            @PathVariable UUID imageId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        imageService.deleteImage(imageId, principal.username());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set primary image",
            description = "Promotes an image to primary for its product. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/images/{imageId}/primary")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<Void> setPrimaryImage(
            @PathVariable UUID imageId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        imageService.setPrimaryImage(imageId, principal.username());
        return ResponseEntity.noContent().build();
    }
}
