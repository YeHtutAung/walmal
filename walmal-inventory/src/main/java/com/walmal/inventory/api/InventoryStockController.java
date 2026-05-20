package com.walmal.inventory.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.inventory.api.dto.response.StockAvailabilityResponse;
import com.walmal.inventory.api.dto.response.StockLevelResponse;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.inventory.application.InventoryQueryService;
import com.walmal.inventory.api.dto.request.AdjustStockRequest;
import com.walmal.inventory.api.dto.request.TransferStockRequest;
import com.walmal.common.auth.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for stock level reads and adjustments.
 * Base path: /api/v1/inventory/stock
 */
@RestController
@RequestMapping("/api/v1/inventory/stock")
@Tag(name = "Inventory Stock", description = "Stock level reads and warehouse adjustments")
public class InventoryStockController {

    private final InventoryQueryService queryService;
    private final InventoryAdjustmentService adjustmentService;

    public InventoryStockController(InventoryQueryService queryService,
                                     InventoryAdjustmentService adjustmentService) {
        this.queryService = queryService;
        this.adjustmentService = adjustmentService;
    }

    @GetMapping("/{variantId}/{locationId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get stock level at a specific location",
               description = "Returns available and reserved quantities. Results cached 30 s.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Stock record not found")
    })
    public ApiResponse<StockLevelResponse> getStockLevel(
            @PathVariable UUID variantId,
            @PathVariable UUID locationId) {
        return ApiResponse.ok(queryService.getStockLevel(variantId, locationId));
    }

    @GetMapping("/{variantId}/availability")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get aggregated availability across all locations",
               description = "Returns total available + per-location breakdown. Cached 60 s.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ApiResponse<StockAvailabilityResponse> getAggregatedAvailability(
            @PathVariable UUID variantId) {
        return ApiResponse.ok(queryService.getAggregatedAvailability(variantId));
    }

    @GetMapping("/{variantId}/check")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Check if quantity is available for a variant",
               description = "Returns true if total available >= requested quantity.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ApiResponse<Boolean> checkAvailability(
            @PathVariable UUID variantId,
            @RequestParam int quantity) {
        return ApiResponse.ok(queryService.checkAvailability(variantId, quantity));
    }

    @PostMapping("/adjust")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Apply a stock adjustment",
               description = "Positive delta = inflow; negative delta = outflow. Warehouse/Admin only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Adjusted"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Business rule violation")
    })
    public void adjustStock(@Valid @RequestBody AdjustStockRequest request,
                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        adjustmentService.adjustStock(
                request.variantId(), request.locationId(),
                request.delta(), request.reason(), principal.username());
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Transfer stock between locations",
               description = "Atomic source decrement + destination increment. Warehouse/Admin only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Transferred"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Insufficient stock")
    })
    public void transferStock(@Valid @RequestBody TransferStockRequest request,
                               @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        adjustmentService.transferStock(
                request.variantId(), request.fromLocationId(), request.toLocationId(),
                request.quantity(), principal.username());
    }
}
