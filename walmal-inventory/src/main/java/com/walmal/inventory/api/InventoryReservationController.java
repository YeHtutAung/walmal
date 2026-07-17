package com.walmal.inventory.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.inventory.api.dto.request.PosConflictRequest;
import com.walmal.inventory.api.dto.request.ReserveStockRequest;
import com.walmal.inventory.api.dto.response.ReservationResponse;
import com.walmal.inventory.application.InventoryAdminService;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.inventory.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for inventory reservation lifecycle.
 * Base path: /api/v1/inventory/reservations
 *
 * <p>In MVP this controller is called by the Order module (internal service-to-service).
 * Role requirement: ORDER_SERVICE internal service account.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/reservations")
@Tag(name = "Inventory Reservations", description = "Stock reservation lifecycle for order processing")
public class InventoryReservationController {

    private final InventoryReservationService reservationService;
    private final InventoryAdminService adminService;

    public InventoryReservationController(InventoryReservationService reservationService,
                                           InventoryAdminService adminService) {
        this.reservationService = reservationService;
        this.adminService = adminService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    @Operation(summary = "List reservations (paginated)",
               description = "Returns reservations optionally filtered by status. Admin/Warehouse roles only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ApiResponse<Page<ReservationResponse>> listReservations(
            @RequestParam(required = false) @Nullable String status,
            @PageableDefault(size = 20) Pageable pageable) {
        ReservationStatus statusEnum = (status != null && !status.isBlank())
                ? ReservationStatus.valueOf(status.toUpperCase())
                : null;
        return ApiResponse.ok(adminService.listReservations(statusEnum, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reserve stock for an order",
               description = "Reserves stock for all line items. Validates variants active. " +
                             "Returns 201 on success, 409 on insufficient stock.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Reserved"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Insufficient stock or inactive variant")
    })
    public ApiResponse<Void> reserveStock(@Valid @RequestBody ReserveStockRequest request) {
        reservationService.reserveStock(
                request.orderId(),
                request.items().stream()
                        .map(i -> new InventoryReservationService.ReservationLineItem(
                                i.variantId(), i.locationId(), i.quantity()))
                        .collect(Collectors.toList()));
        return ApiResponse.ok("Stock reserved", null);
    }

    @PostMapping("/{orderId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // Ops-only stock-lifecycle mutation. In-process the order module calls the
    // InventoryReservationService interface directly; over HTTP this is a manual
    // ops action, so it must not be reachable by any authenticated customer.
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Confirm reservations on payment success",
               description = "Transitions PENDING → CONFIRMED. Stock permanently deducted.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Confirmed"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No PENDING reservations for this order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public void confirmReservation(@PathVariable UUID orderId) {
        reservationService.confirmReservation(orderId);
    }

    @PostMapping("/{orderId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // Ops-only stock-lifecycle mutation (see confirm above).
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Release reservations on order cancellation",
               description = "Transitions PENDING → RELEASED. Returns stock to available pool.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Released"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No PENDING reservations for this order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public void releaseReservation(@PathVariable UUID orderId,
                                    @RequestParam(defaultValue = "CANCELLED") String conflictReason) {
        reservationService.releaseReservation(
                orderId,
                com.walmal.inventory.domain.ConflictReason.valueOf(conflictReason));
    }

    @PostMapping("/resolve-conflict")
    // Ops-only. The POS module resolves conflicts in-process via the
    // InventoryReservationService interface (PosSyncItemProcessor), NOT this
    // HTTP endpoint — so gating it to ops roles breaks no sync flow.
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Resolve POS offline sync conflict",
               description = "Determines POS priority vs buffer stock vs BUFFER_EXHAUSTED. " +
                             "Manual ops action; the POS module resolves in-process via the " +
                             "InventoryReservationService interface. Returns the resolution outcome.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Resolved — outcome in body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<com.walmal.inventory.application.ConflictResolutionResult> resolveConflict(
            @Valid @RequestBody PosConflictRequest request) {
        com.walmal.inventory.application.ConflictResolutionResult result = reservationService.resolveConflict(
                request.posSaleId(), request.variantId(), request.locationId(),
                request.quantity(), request.posSaleTimestamp(), request.webOrderId());
        return ResponseEntity.ok(result);
    }
}
