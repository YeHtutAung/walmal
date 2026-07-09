package com.walmal.inventory.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.model.ApiResponse;
import com.walmal.inventory.api.dto.request.CreateLocationRequest;
import com.walmal.inventory.api.dto.response.LocationResponse;
import com.walmal.inventory.application.InventoryLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for inventory location management.
 * Base path: /api/v1/inventory/locations
 */
@RestController
@RequestMapping("/api/v1/inventory/locations")
@Tag(name = "Inventory Locations", description = "Manage stock locations (warehouses, stores, virtual)")
public class InventoryLocationController {

    private final InventoryLocationService locationService;

    public InventoryLocationController(InventoryLocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/default")
    @Operation(summary = "Get default active location", description = "Returns the first active non-buffer location; no authentication required")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No active location found")
    })
    public ApiResponse<LocationResponse> getDefaultLocation() {
        return ApiResponse.ok(locationService.getDefaultLocation());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "List all locations", description = "Returns all inventory locations")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<List<LocationResponse>> listLocations() {
        return ApiResponse.ok(locationService.listLocations());
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Get location by ID")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    })
    public ApiResponse<LocationResponse> getLocation(@PathVariable UUID locationId) {
        return ApiResponse.ok(locationService.getLocation(locationId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new location", description = "Admin only")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<LocationResponse> createLocation(
            @Valid @RequestBody CreateLocationRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok("Location created",
                locationService.createLocation(request, principal.username()));
    }

    @PutMapping("/{locationId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a location", description = "Admin only")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found")
    })
    public void deactivateLocation(@PathVariable UUID locationId,
                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        locationService.deactivateLocation(locationId, principal.username());
    }
}
