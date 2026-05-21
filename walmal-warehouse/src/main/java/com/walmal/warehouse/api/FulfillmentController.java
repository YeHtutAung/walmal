package com.walmal.warehouse.api;

import com.walmal.common.model.ApiResponse;
import com.walmal.warehouse.api.dto.AdvanceStatusRequest;
import com.walmal.warehouse.api.dto.ShipFulfillmentRequest;
import com.walmal.warehouse.api.dto.UpdatePickedQuantityRequest;
import com.walmal.warehouse.application.WarehouseFulfillmentService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for warehouse fulfillment lifecycle.
 * Base path: {@code /api/v1/warehouse/fulfillments}
 *
 * <p>Role requirements:
 * <ul>
 *   <li>GET endpoints: WAREHOUSE_OPERATOR or ADMIN</li>
 *   <li>State-change endpoints: WAREHOUSE_OPERATOR</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/warehouse/fulfillments")
@Tag(name = "Warehouse Fulfillments", description = "Fulfillment lifecycle management — picking, packing, shipping")
public class FulfillmentController {

    private final WarehouseFulfillmentService fulfillmentService;

    public FulfillmentController(WarehouseFulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('WAREHOUSE_OPERATOR', 'ADMIN')")
    @Operation(summary = "Get fulfillment by order ID",
               description = "Returns full fulfillment detail including all lines, shipment info, and current order status.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fulfillment not found")
    })
    public ApiResponse<FulfillmentDetailDto> getFulfillment(@PathVariable UUID orderId) {
        return ApiResponse.ok(fulfillmentService.getFulfillment(orderId));
    }

    @PostMapping("/{orderId}/advance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('WAREHOUSE_OPERATOR')")
    @Operation(summary = "Advance fulfillment status",
               description = "Valid targets: PICKING (from PENDING), PACKED (from PICKING). Use /ship for PACKED→SHIPPED.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Status advanced"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid transition or missing carrier info"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fulfillment not found")
    })
    public void advanceStatus(@PathVariable UUID orderId,
                               @Valid @RequestBody AdvanceStatusRequest request) {
        fulfillmentService.advanceStatus(orderId, request.targetStatus(), request.notes());
    }

    @PostMapping("/{orderId}/ship")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('WAREHOUSE_OPERATOR')")
    @Operation(summary = "Ship the fulfillment (PACKED → SHIPPED)",
               description = "Finalises the shipment. Writes any WRITE_OFF adjustments for discrepant lines " +
                             "(audit log written before each adjustment). Marks order as FULFILLED. " +
                             "Publishes warehouse.fulfillment.shipped.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Shipped"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid state or missing required fields"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fulfillment not found")
    })
    public void shipFulfillment(@PathVariable UUID orderId,
                                 @Valid @RequestBody ShipFulfillmentRequest request) {
        fulfillmentService.shipFulfillment(orderId, request.carrier(), request.trackingNumber(), request.notes());
    }

    @PutMapping("/lines/{lineId}/picked")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('WAREHOUSE_OPERATOR')")
    @Operation(summary = "Record picked quantity for a fulfillment line",
               description = "Updates quantity_picked. Only valid while fulfillment is in PICKING status.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid quantity or wrong status"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Line not found")
    })
    public void recordPickedQuantity(@PathVariable UUID lineId,
                                      @Valid @RequestBody UpdatePickedQuantityRequest request) {
        fulfillmentService.recordPickedQuantity(lineId, request.quantityPicked());
    }

    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('WAREHOUSE_OPERATOR')")
    @Operation(summary = "Cancel a fulfillment",
               description = "Only valid from PENDING or PICKING status. PACKED and SHIPPED are non-cancellable.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Cancelled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Fulfillment not cancellable"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Fulfillment not found")
    })
    public void cancelFulfillment(@PathVariable UUID orderId) {
        fulfillmentService.cancelFulfillment(orderId);
    }
}
