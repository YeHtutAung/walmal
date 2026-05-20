package com.walmal.inventory.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.api.dto.request.AdjustStockRequest;
import com.walmal.inventory.api.dto.response.StockLevelResponse;
import com.walmal.inventory.application.InventoryAdjustmentService;
import com.walmal.inventory.application.InventoryQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link InventoryStockController}.
 * Imports AuthSecurityConfig to exercise the full security filter chain.
 */
@WebMvcTest(controllers = InventoryStockController.class)
@Import({AuthSecurityConfig.class, InventoryExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean InventoryQueryService queryService;
    @MockitoBean InventoryAdjustmentService adjustmentService;
    @MockitoBean TokenValidationService tokenValidationService;

    // ── GET stock level ───────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return401_when_unauthenticated")
    void should_return401_when_unauthenticated() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/inventory/stock/{variantId}/{locationId}",
                        variantId, locationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return200_when_getStockLevel")
    @WithMockUser(username = "warehouse1", roles = "WAREHOUSE_MANAGER")
    void should_return200_when_getStockLevel() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        StockLevelResponse response = new StockLevelResponse(
                variantId, locationId, "Main Warehouse", 50, 5, 10);

        when(queryService.getStockLevel(variantId, locationId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/inventory/stock/{variantId}/{locationId}",
                        variantId, locationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.availableQuantity").value(50));
    }

    @Test
    @DisplayName("should_return409_when_insufficientStock")
    void should_return409_when_insufficientStock() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        when(queryService.getStockLevel(variantId, locationId))
                .thenThrow(new BusinessRuleException("Insufficient stock"));

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), "staff1", "STAFF");

        mockMvc.perform(get("/api/v1/inventory/stock/{variantId}/{locationId}",
                        variantId, locationId)
                        .with(authentication(buildAuth(principal))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("should_return404_when_stockNotFound")
    void should_return404_when_stockNotFound() throws Exception {
        UUID variantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        when(queryService.getStockLevel(variantId, locationId))
                .thenThrow(new ResourceNotFoundException("InventoryStock", variantId));

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                UUID.randomUUID(), "staff1", "STAFF");

        mockMvc.perform(get("/api/v1/inventory/stock/{variantId}/{locationId}",
                        variantId, locationId)
                        .with(authentication(buildAuth(principal))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should_return403_when_customerAccessesAdjustEndpoint")
    void should_return403_when_customerAccessesAdjustEndpoint() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");

        AdjustStockRequest request = new AdjustStockRequest(
                UUID.randomUUID(), UUID.randomUUID(), -5, "shrinkage");

        mockMvc.perform(post("/api/v1/inventory/stock/adjust")
                        .with(authentication(buildAuth(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return403_when_staffAccessesAdminEndpoint")
    void should_return403_when_staffAccessesAdminEndpoint() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(
                UUID.randomUUID(), "staff1", "STAFF");

        AdjustStockRequest request = new AdjustStockRequest(
                UUID.randomUUID(), UUID.randomUUID(), 10, "restock");

        mockMvc.perform(post("/api/v1/inventory/stock/adjust")
                        .with(authentication(buildAuth(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }
}
