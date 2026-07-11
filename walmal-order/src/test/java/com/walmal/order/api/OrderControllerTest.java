package com.walmal.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.api.dto.CreateOrderRequest;
import com.walmal.order.api.dto.OrderLineItemRequest;
import com.walmal.order.api.dto.ShippingAddressRequest;
import com.walmal.order.application.OrderAdminService;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.OrderFulfillmentService;
import com.walmal.order.application.OrderQueryService;
import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderSummaryDto;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.domain.ShippingAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest for {@link OrderController}.
 * Imports AuthSecurityConfig to exercise the full security filter chain.
 */
@WebMvcTest(controllers = OrderController.class)
@Import({AuthSecurityConfig.class, OrderExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean OrderCreationService orderCreationService;
    @MockitoBean OrderQueryService orderQueryService;
    @MockitoBean OrderFulfillmentService orderFulfillmentService;
    @MockitoBean OrderAdminService orderAdminService;
    @MockitoBean TokenValidationService tokenValidationService;

    // ── GET order ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return401_when_unauthenticated_getOrder")
    void should_return401_when_unauthenticated_getOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return200_when_getOrder_asOwner")
    void should_return200_when_getOrder_asOwner() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AuthenticatedPrincipal owner = new AuthenticatedPrincipal(ownerId, "customer1", "CUSTOMER");
        OrderDetailDto dto = new OrderDetailDto(
                orderId, ownerId, OrderStatus.CONFIRMED,
                BigDecimal.valueOf(99.99), "USD",
                new ShippingAddress("1 Main St", null, "City", "US", "12345"),
                List.of(), Instant.now());

        when(orderQueryService.getOrder(orderId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(authentication(buildAuth(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("should_return409_when_customerAccessesOtherUsersOrder")
    void should_return409_when_customerAccessesOtherUsersOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AuthenticatedPrincipal other = new AuthenticatedPrincipal(UUID.randomUUID(), "other", "CUSTOMER");
        OrderDetailDto dto = new OrderDetailDto(
                orderId, ownerId, OrderStatus.CONFIRMED,
                BigDecimal.valueOf(99.99), "USD",
                new ShippingAddress("1 Main St", null, "City", "US", "12345"),
                List.of(), Instant.now());

        when(orderQueryService.getOrder(orderId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(authentication(buildAuth(other))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("should_return200_when_adminAccessesAnyOrder")
    void should_return200_when_adminAccessesAnyOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        OrderDetailDto dto = new OrderDetailDto(
                orderId, UUID.randomUUID(), OrderStatus.CONFIRMED,
                BigDecimal.valueOf(99.99), "USD",
                new ShippingAddress("1 Main St", null, "City", "US", "12345"),
                List.of(), Instant.now());

        when(orderQueryService.getOrder(orderId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("should_return404_when_orderNotFound")
    @WithMockUser(username = "customer1", roles = "CUSTOMER")
    void should_return404_when_orderNotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderQueryService.getOrder(orderId))
                .thenThrow(new ResourceNotFoundException("Order", orderId));

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                .andExpect(status().isNotFound());
    }

    // ── Admin list ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_passStatusFilterToService_when_adminListsOrdersWithStatusParam")
    void should_passStatusFilterToService_when_adminListsOrdersWithStatusParam() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        when(orderAdminService.listAllOrders(eq(OrderStatus.CANCELLED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/orders/admin")
                        .param("status", "CANCELLED")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderAdminService).listAllOrders(eq(OrderStatus.CANCELLED), any(Pageable.class));
    }

    @Test
    @DisplayName("should_passNullStatusToService_when_adminListsOrdersWithoutStatusParam")
    void should_passNullStatusToService_when_adminListsOrdersWithoutStatusParam() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        when(orderAdminService.listAllOrders(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/orders/admin")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderAdminService).listAllOrders(isNull(), any(Pageable.class));
    }

    // ── Admin daily summary ──────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200AndSummaryList_when_adminRequestsDailySummary")
    void should_return200AndSummaryList_when_adminRequestsDailySummary() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        when(orderAdminService.getDailySummary()).thenReturn(List.of(
                new DailyOrderSummaryDto(LocalDate.of(2026, 7, 11), 3, new BigDecimal("100.00"), "USD")
        ));
        mockMvc.perform(get("/api/v1/orders/admin/daily-summary")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderCount").value(3));
    }

    @Test
    @DisplayName("should_return403_when_customerRequestsDailySummary")
    void should_return403_when_customerRequestsDailySummary() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), "cust", "CUSTOMER");
        mockMvc.perform(get("/api/v1/orders/admin/daily-summary")
                        .with(authentication(buildAuth(customer))))
                .andExpect(status().isForbidden());
    }

    // ── GET order status ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200_when_getOrderStatus_asOwner")
    void should_return200_when_getOrderStatus_asOwner() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AuthenticatedPrincipal owner = new AuthenticatedPrincipal(ownerId, "customer1", "CUSTOMER");
        OrderDetailDto dto = new OrderDetailDto(
                orderId, ownerId, OrderStatus.PENDING,
                BigDecimal.valueOf(99.99), "USD", null, List.of(), Instant.now());

        when(orderQueryService.getOrder(orderId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{orderId}/status", orderId)
                        .with(authentication(buildAuth(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("PENDING"));
    }

    @Test
    @DisplayName("should_return409_when_customerAccessesOtherUsersOrderStatus")
    void should_return409_when_customerAccessesOtherUsersOrderStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        AuthenticatedPrincipal other = new AuthenticatedPrincipal(UUID.randomUUID(), "other", "CUSTOMER");
        OrderDetailDto dto = new OrderDetailDto(
                orderId, UUID.randomUUID(), OrderStatus.PENDING,
                BigDecimal.valueOf(99.99), "USD", null, List.of(), Instant.now());

        when(orderQueryService.getOrder(orderId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{orderId}/status", orderId)
                        .with(authentication(buildAuth(other))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── GET list orders ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200_when_listMyOrders")
    void should_return200_when_listMyOrders() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");

        OrderSummaryDto summary = new OrderSummaryDto(
                UUID.randomUUID(), OrderStatus.PENDING,
                BigDecimal.valueOf(99.99), "USD", Instant.now());
        Page<OrderSummaryDto> page = new PageImpl<>(List.of(summary));

        when(orderQueryService.listOrdersByUser(any(UUID.class), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/orders")
                        .with(authentication(buildAuth(customer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ── POST create order ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return201_when_createOrder")
    void should_return201_when_createOrder() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");
        UUID newOrderId = UUID.randomUUID();

        when(orderCreationService.createOrder(any(), any(), any(), any()))
                .thenReturn(newOrderId);

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderLineItemRequest(UUID.randomUUID(), UUID.randomUUID(), 1)),
                new ShippingAddressRequest("1 Main St", null, "City", "US", "12345"),
                "USD", null);

        mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(buildAuth(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("should_return201_when_guestOrder_with_guestEmail")
    void should_return201_when_guestOrder_with_guestEmail() throws Exception {
        UUID newOrderId = UUID.randomUUID();
        when(orderCreationService.createGuestOrder(any(), any(), any(), any()))
                .thenReturn(newOrderId);

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderLineItemRequest(UUID.randomUUID(), UUID.randomUUID(), 1)),
                new ShippingAddressRequest("1 Main St", null, "City", "US", "12345"),
                "USD", "guest@example.com");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("should_return409_when_variantInactive")
    void should_return409_when_variantInactive() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");

        when(orderCreationService.createOrder(any(), any(), any(), any()))
                .thenThrow(new BusinessRuleException("Variant is not active"));

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderLineItemRequest(UUID.randomUUID(), UUID.randomUUID(), 1)),
                new ShippingAddressRequest("1 Main St", null, "City", "US", "12345"),
                "USD", null);

        mockMvc.perform(post("/api/v1/orders")
                        .with(authentication(buildAuth(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // ── PUT cancel ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return204_when_cancelOrder")
    void should_return204_when_cancelOrder() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/orders/{orderId}/cancel", orderId)
                        .with(authentication(buildAuth(customer))))
                .andExpect(status().isNoContent());
    }

    // ── PUT fulfill ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return204_when_fulfillOrder_asWarehouseStaff")
    void should_return204_when_fulfillOrder_asWarehouseStaff() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(
                UUID.randomUUID(), "warehouse1", "WAREHOUSE_STAFF");
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", orderId)
                        .with(authentication(buildAuth(staff))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("should_return403_when_customerTriesToFulfill")
    void should_return403_when_customerTriesToFulfill() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/orders/{orderId}/fulfill", orderId)
                        .with(authentication(buildAuth(customer))))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }
}
