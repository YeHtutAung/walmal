package com.walmal.order.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.model.ApiResponse;
import com.walmal.order.api.dto.CreateOrderRequest;
import com.walmal.order.api.dto.UpdateOrderStatusRequest;
import com.walmal.order.application.OrderAdminService;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderAdminSummaryDto;
import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.application.dto.OrderSummaryDto;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for order operations.
 * Base path: {@code /api/v1/orders}
 *
 * <p>No business logic lives here — all work is delegated to service interfaces.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order creation, status management, and fulfilment")
public class OrderController {

    private final OrderCreationService orderCreationService;
    private final OrderQueryService orderQueryService;
    private final OrderFulfillmentService orderFulfillmentService;
    private final OrderAdminService orderAdminService;

    public OrderController(OrderCreationService orderCreationService,
                            OrderQueryService orderQueryService,
                            OrderFulfillmentService orderFulfillmentService,
                            OrderAdminService orderAdminService) {
        this.orderCreationService = orderCreationService;
        this.orderQueryService = orderQueryService;
        this.orderFulfillmentService = orderFulfillmentService;
        this.orderAdminService = orderAdminService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Place a new order",
               description = "Creates a new order. Authenticated users use their session; " +
                             "guests must supply guestEmail in the request body.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Order created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Business rule violation (inactive variant, insufficient stock)")
    })
    public ApiResponse<UUID> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {

        ShippingAddress address = mapAddress(request);
        java.util.List<OrderLineItem> items = request.items().stream()
                .map(i -> new OrderLineItem(i.variantId(), i.locationId(), i.quantity()))
                .toList();

        UUID orderId;
        if (principal != null) {
            orderId = orderCreationService.createOrder(principal.userId(), items, address, request.currency());
        } else {
            if (request.guestEmail() == null || request.guestEmail().isBlank()) {
                throw new com.walmal.common.exception.BusinessRuleException(
                        "guestEmail is required for guest checkout");
            }
            orderId = orderCreationService.createGuestOrder(request.guestEmail(), items, address, request.currency());
        }
        return ApiResponse.ok("Order created", orderId);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get order details",
               description = "Returns full order details including all line items.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not your order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ApiResponse<OrderDetailDto> getOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        OrderDetailDto order = orderQueryService.getOrder(orderId);
        verifyOrderOwnership(order.userId(), principal);
        return ApiResponse.ok(order);
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my orders",
               description = "Returns a paginated list of orders placed by the authenticated user.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ApiResponse<Page<OrderSummaryDto>> listMyOrders(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(orderQueryService.listOrdersByUser(principal.userId(), pageable));
    }

    @GetMapping("/{orderId}/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get order status",
               description = "Returns the current lifecycle status of an order.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not your order"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ApiResponse<OrderStatus> getOrderStatus(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        OrderDetailDto order = orderQueryService.getOrder(orderId);
        verifyOrderOwnership(order.userId(), principal);
        return ApiResponse.ok(order.status());
    }

    @PutMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cancel a pending order",
               description = "Cancels a PENDING order, releases inventory, and refunds (stub for MVP).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Cancelled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Order is not in a cancellable state")
    })
    public void cancelOrder(@PathVariable UUID orderId,
                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Ownership check BEFORE cancelling — a customer may cancel only their
        // own order (ADMIN/STAFF may cancel any). Without this, cancelOrder used
        // the principal id for the audit row only, leaving the endpoint open to
        // cancelling anyone's order by id.
        OrderDetailDto order = orderQueryService.getOrder(orderId);
        verifyOrderOwnership(order.userId(), principal);
        orderCreationService.cancelOrder(orderId, principal.userId());
    }

    @PutMapping("/{orderId}/fulfill")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    @Operation(summary = "Mark an order as fulfilled",
               description = "Transitions a CONFIRMED order to FULFILLED. Warehouse staff or Admin only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Fulfilled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Order not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Order is not in CONFIRMED state")
    })
    public void fulfillOrder(@PathVariable UUID orderId) {
        orderFulfillmentService.markFulfilled(orderId);
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary = "List all orders (admin)",
               description = "Returns a paginated list of all orders across all users. Admin and Staff only.")
    public ApiResponse<Page<OrderAdminSummaryDto>> listAllOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(orderAdminService.listAllOrders(status, pageable));
    }

    @GetMapping("/admin/daily-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary = "Daily order/revenue summary (admin)",
               description = "Returns a 30-day, zero-filled daily order count + FULFILLED revenue series. Admin and Staff only.")
    public ApiResponse<List<DailyOrderSummaryDto>> getDailySummary() {
        return ApiResponse.ok(orderAdminService.getDailySummary());
    }

    @GetMapping("/admin/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary = "Search orders (admin)",
               description = "Matches order-ID prefix or guest-email substring, case-insensitive; " +
                             "LIKE wildcards in q are treated literally. " +
                             "q under 2 chars returns an empty page. Admin and Staff only.")
    public ApiResponse<Page<OrderAdminSummaryDto>> searchOrders(
            @RequestParam(defaultValue = "") String q,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ApiResponse.ok(orderAdminService.searchOrders(q, pageable));
    }

    @PatchMapping("/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary = "Update order status (admin)",
               description = "Manually transitions an order to a new status. " +
                             "Valid: PENDING→CONFIRMED, PENDING→CANCELLED, CONFIRMED→FULFILLED.")
    public void updateOrderStatus(@PathVariable UUID orderId,
                                   @Valid @RequestBody UpdateOrderStatusRequest request) {
        orderAdminService.updateStatus(orderId, request.status(), request.reason());
    }

    // ── Ownership verification ──────────────────────────────────────────────

    private void verifyOrderOwnership(UUID orderUserId, AuthenticatedPrincipal principal) {
        String role = principal.role();
        if ("ADMIN".equals(role) || "STAFF".equals(role)) {
            return;
        }
        if (!orderUserId.equals(principal.userId())) {
            throw new BusinessRuleException("Access denied: you can only view your own orders");
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private ShippingAddress mapAddress(CreateOrderRequest request) {
        if (request.shippingAddress() == null) {
            return null;
        }
        return new ShippingAddress(
                request.shippingAddress().line1(),
                request.shippingAddress().line2(),
                request.shippingAddress().city(),
                request.shippingAddress().country(),
                request.shippingAddress().postalCode());
    }
}
