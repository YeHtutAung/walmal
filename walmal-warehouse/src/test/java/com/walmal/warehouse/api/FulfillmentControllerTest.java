package com.walmal.warehouse.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.domain.OrderStatus;
import com.walmal.warehouse.api.dto.AdvanceStatusRequest;
import com.walmal.warehouse.api.dto.ShipFulfillmentRequest;
import com.walmal.warehouse.api.dto.UpdatePickedQuantityRequest;
import com.walmal.warehouse.application.WarehouseFulfillmentService;
import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.domain.FulfillmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = FulfillmentController.class)
@Import({AuthSecurityConfig.class, WarehouseExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-warehouse-controller-tests-min32",
        "walmal.jwt.access-token-expire-minutes=15"
})
class FulfillmentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WarehouseFulfillmentService fulfillmentService;
    @MockitoBean TokenValidationService tokenValidationService;

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID LINE_ID  = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken warehouseAuth() {
        return new UsernamePasswordAuthenticationToken(
                "operator", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR")));
    }

    private UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private FulfillmentDetailDto buildDetail(FulfillmentStatus status) {
        return new FulfillmentDetailDto(
                UUID.randomUUID(), ORDER_ID, UUID.randomUUID(),
                status, OrderStatus.CONFIRMED,
                "{}", List.of(), null, null,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("should_return200_when_getFulfillment_asAdmin")
    void should_return200_when_getFulfillment_asAdmin() throws Exception {
        when(fulfillmentService.getFulfillment(ORDER_ID)).thenReturn(buildDetail(FulfillmentStatus.PENDING));

        mockMvc.perform(get("/api/v1/warehouse/fulfillments/{orderId}", ORDER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @DisplayName("should_return404_when_fulfillmentNotFound")
    void should_return404_when_fulfillmentNotFound() throws Exception {
        when(fulfillmentService.getFulfillment(any())).thenThrow(
                new ResourceNotFoundException("Fulfillment", ORDER_ID));

        mockMvc.perform(get("/api/v1/warehouse/fulfillments/{orderId}", ORDER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should_return403_when_getFulfillment_asUnauthorizedRole")
    void should_return403_when_getFulfillment_asUnauthorizedRole() throws Exception {
        UsernamePasswordAuthenticationToken posAuth = new UsernamePasswordAuthenticationToken(
                "posop", "pw", List.of(new SimpleGrantedAuthority("ROLE_POS_OPERATOR")));

        mockMvc.perform(get("/api/v1/warehouse/fulfillments/{orderId}", ORDER_ID)
                        .with(authentication(posAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return204_when_advanceStatusToPicking")
    void should_return204_when_advanceStatusToPicking() throws Exception {
        AdvanceStatusRequest req = new AdvanceStatusRequest(FulfillmentStatus.PICKING, "Start picking");

        mockMvc.perform(post("/api/v1/warehouse/fulfillments/{orderId}/advance", ORDER_ID)
                        .with(authentication(warehouseAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(fulfillmentService).advanceStatus(ORDER_ID, FulfillmentStatus.PICKING, "Start picking");
    }

    @Test
    @DisplayName("should_return400_when_advanceStatus_invalidTransition")
    void should_return400_when_advanceStatus_invalidTransition() throws Exception {
        AdvanceStatusRequest req = new AdvanceStatusRequest(FulfillmentStatus.PACKED, null);
        doThrow(new BusinessRuleException("Cannot transition"))
                .when(fulfillmentService).advanceStatus(any(), any(), any());

        mockMvc.perform(post("/api/v1/warehouse/fulfillments/{orderId}/advance", ORDER_ID)
                        .with(authentication(warehouseAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return204_when_shipFulfillment")
    void should_return204_when_shipFulfillment() throws Exception {
        ShipFulfillmentRequest req = new ShipFulfillmentRequest("FedEx", "TRK-001", null);

        mockMvc.perform(post("/api/v1/warehouse/fulfillments/{orderId}/ship", ORDER_ID)
                        .with(authentication(warehouseAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(fulfillmentService).shipFulfillment(ORDER_ID, "FedEx", "TRK-001", null);
    }

    @Test
    @DisplayName("should_return204_when_recordPickedQuantity")
    void should_return204_when_recordPickedQuantity() throws Exception {
        UpdatePickedQuantityRequest req = new UpdatePickedQuantityRequest(2);

        mockMvc.perform(put("/api/v1/warehouse/fulfillments/lines/{lineId}/picked", LINE_ID)
                        .with(authentication(warehouseAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        verify(fulfillmentService).recordPickedQuantity(LINE_ID, 2);
    }

    @Test
    @DisplayName("should_return204_when_cancelFulfillment")
    void should_return204_when_cancelFulfillment() throws Exception {
        mockMvc.perform(post("/api/v1/warehouse/fulfillments/{orderId}/cancel", ORDER_ID)
                        .with(authentication(warehouseAuth())))
                .andExpect(status().isNoContent());

        verify(fulfillmentService).cancelFulfillment(ORDER_ID);
    }
}
