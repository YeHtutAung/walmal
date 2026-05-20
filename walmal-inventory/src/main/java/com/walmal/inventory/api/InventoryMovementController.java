package com.walmal.inventory.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.inventory.api.dto.response.MovementResponse;
import com.walmal.inventory.application.InventoryMovementQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for inventory movement history (read-only).
 * Base path: /api/v1/inventory/movements
 *
 * <p>Movements are an insert-only audit trail — no create/update/delete endpoints.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/movements")
@Tag(name = "Inventory Movements", description = "Read-only stock movement history")
public class InventoryMovementController {

    private final InventoryMovementQueryService movementQueryService;

    public InventoryMovementController(InventoryMovementQueryService movementQueryService) {
        this.movementQueryService = movementQueryService;
    }

    @GetMapping("/variant/{variantId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Get movement history for a variant",
               description = "Returns paginated movement records for a specific product variant.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<Page<MovementResponse>> getByVariant(
            @PathVariable UUID variantId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(movementQueryService.getMovementsByVariant(variantId, pageable));
    }

    @GetMapping("/location/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Get movement history for a location",
               description = "Returns paginated movement records for a specific inventory location.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<Page<MovementResponse>> getByLocation(
            @PathVariable UUID locationId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(movementQueryService.getMovementsByLocation(locationId, pageable));
    }
}
