package com.walmal.product.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.model.ApiResponse;
import com.walmal.product.api.dto.request.CreateCategoryRequest;
import com.walmal.product.api.dto.request.CreateProductRequest;
import com.walmal.product.api.dto.request.CreateVariantRequest;
import com.walmal.product.api.dto.request.SetPriceRequest;
import com.walmal.product.api.dto.request.UpdateProductRequest;
import com.walmal.product.api.dto.response.CategoryResponse;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.ProductSearchService;
import com.walmal.product.application.dto.CategoryTreeDto;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.ProductSummaryDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for product catalog, variant, category, and pricing operations.
 * Base path: {@code /api/v1/product}
 *
 * <p>Role access:
 * <ul>
 *   <li>Read operations: authenticated users (any role)</li>
 *   <li>Create/update product/variant/category: ADMIN or STAFF</li>
 *   <li>Deactivate product or variant: ADMIN only</li>
 *   <li>Set price: ADMIN only</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/product")
@Tag(name = "Product", description = "Product catalog, variant management, pricing, and category operations")
public class ProductController {

    private final ProductManagementService managementService;
    private final ProductSearchService searchService;
    private final ProductCatalogService catalogService;

    public ProductController(ProductManagementService managementService,
                              ProductSearchService searchService,
                              ProductCatalogService catalogService) {
        this.managementService = managementService;
        this.searchService = searchService;
        this.catalogService = catalogService;
    }

    // ── Category endpoints ────────────────────────────────────────────────────

    @Operation(summary = "Get category tree", description = "Returns the full active category hierarchy")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category tree returned")
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryTreeDto>>> getCategoryTree() {
        return ResponseEntity.ok(ApiResponse.ok(searchService.getCategoryTree()));
    }

    @Operation(summary = "Create category",
            description = "Creates a new product category. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Category created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Category created", managementService.createCategory(request)));
    }

    // ── Product search endpoints ──────────────────────────────────────────────

    @Operation(summary = "Search products",
            description = "Full-text search across product name and brand (ILIKE for MVP)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated results returned")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<ProductSummaryDto>>> searchProducts(
            @RequestParam(required = false, defaultValue = "") String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.searchProducts(q, pageable)));
    }

    @Operation(summary = "List products by category",
            description = "Returns paginated products in the given category")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated results returned")
    @GetMapping("/categories/{categoryId}/products")
    public ResponseEntity<ApiResponse<Page<ProductSummaryDto>>> listByCategory(
            @PathVariable UUID categoryId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.listByCategory(categoryId, pageable)));
    }

    // ── Product CRUD endpoints ────────────────────────────────────────────────

    @Operation(summary = "Get product details",
            description = "Returns full product details by product ID")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found")
    })
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailDto>> getProductDetails(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.getProductDetails(productId)));
    }

    @Operation(summary = "Create product",
            description = "Creates a new product. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Product created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<ProductDetailDto>> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Product created",
                        managementService.createProduct(request, principal.username())));
    }

    @Operation(summary = "Update product",
            description = "Updates product details. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PutMapping("/{productId}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<ProductDetailDto>> updateProductDetails(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Product updated",
                managementService.updateProductDetails(productId, request, principal.username())));
    }

    @Operation(summary = "Deactivate product",
            description = "Sets product status to INACTIVE. Requires ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Product deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    @PostMapping("/{productId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateProduct(
            @PathVariable UUID productId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        managementService.deactivateProduct(productId, principal.username());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Activate product",
            description = "Sets product status back to ACTIVE. Requires ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{productId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> activateProduct(
            @PathVariable UUID productId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        managementService.activateProduct(productId, principal.username());
        return ResponseEntity.noContent().build();
    }

    // ── Variant endpoints ─────────────────────────────────────────────────────

    @Operation(summary = "List variants",
            description = "Returns all variants for the given product")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Variants returned")
    @GetMapping("/{productId}/variants")
    public ResponseEntity<ApiResponse<List<VariantSummaryDto>>> listVariants(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.ok(managementService.listVariants(productId)));
    }

    @Operation(summary = "Get variant by ID",
            description = "Returns variant summary by variantId. Used by the admin inventory list.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Variant found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Variant not found")
    })
    @GetMapping("/variants/{variantId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<VariantSummaryDto>> getVariantById(@PathVariable UUID variantId) {
        return catalogService.findVariantById(variantId)
                .map(v -> ResponseEntity.ok(ApiResponse.ok(v)))
                .orElseThrow(() -> new com.walmal.common.exception.ResourceNotFoundException(
                        "ProductVariant", variantId.toString()));
    }

    @Operation(summary = "Create variant",
            description = "Creates a new variant with initial price. Requires ADMIN or STAFF role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Variant created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN or STAFF role required")
    })
    @PostMapping("/{productId}/variants")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ResponseEntity<ApiResponse<VariantSummaryDto>> createVariant(
            @PathVariable UUID productId,
            @Valid @RequestBody CreateVariantRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Variant created",
                        managementService.createVariant(productId, request, principal.username())));
    }

    @Operation(summary = "Deactivate variant",
            description = "Sets variant status to INACTIVE. Requires ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Variant deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Variant not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    @PostMapping("/{productId}/variants/{variantId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateVariant(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        managementService.deactivateVariant(variantId, principal.username());
        return ResponseEntity.noContent().build();
    }

    // ── Price endpoints ───────────────────────────────────────────────────────

    @Operation(summary = "Set variant price",
            description = "Updates (or creates) the price for a variant. Requires ADMIN role.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Price set"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN role required")
    })
    @PutMapping("/{productId}/variants/{variantId}/price")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PriceDto>> setPrice(
            @PathVariable UUID productId,
            @PathVariable UUID variantId,
            @Valid @RequestBody SetPriceRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Price updated",
                managementService.setPrice(variantId, request, principal.username())));
    }
}
