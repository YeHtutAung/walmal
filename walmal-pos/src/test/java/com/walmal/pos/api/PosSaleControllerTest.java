package com.walmal.pos.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.pos.api.dto.RecordOnlineSaleRequest;
import com.walmal.pos.application.PosSaleService;
import com.walmal.pos.application.dto.PosSaleDto;
import com.walmal.pos.application.dto.PosSaleLineItem;
import com.walmal.pos.domain.SaleMode;
import com.walmal.pos.domain.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link PosSaleController}.
 * Imports AuthSecurityConfig to exercise the full security filter chain.
 */
@WebMvcTest(controllers = PosSaleController.class)
@Import({AuthSecurityConfig.class, PosExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-pos-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class PosSaleControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean PosSaleService posSaleService;
    @MockitoBean TokenValidationService tokenValidationService;

    private final UUID terminalId = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private final UUID variantId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final UUID cashierId = UUID.randomUUID();

    @Test
    @DisplayName("should_return201_when_onlineSaleRecorded")
    void should_return201_when_onlineSaleRecorded() throws Exception {
        AuthenticatedPrincipal operator = new AuthenticatedPrincipal(cashierId, "operator1", "POS_OPERATOR");
        UUID saleId = UUID.randomUUID();

        PosSaleDto dto = new PosSaleDto(
                saleId, terminalId, UUID.randomUUID(),
                Instant.now(), BigDecimal.valueOf(99.98), "SGD",
                SaleMode.ONLINE, SyncStatus.N_A, List.of());

        when(posSaleService.recordOnlineSale(any(), any(), any(), any(), any())).thenReturn(dto);

        RecordOnlineSaleRequest request = new RecordOnlineSaleRequest(
                terminalId,
                List.of(new PosSaleLineItem(variantId, locationId, 2)),
                cashierId,
                "SGD");

        mockMvc.perform(post("/api/v1/pos/sales")
                        .with(authentication(buildAuth(operator)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.saleMode").value("ONLINE"));
    }

    @Test
    @DisplayName("should_return400_when_variantInactive")
    void should_return400_when_variantInactive() throws Exception {
        AuthenticatedPrincipal operator = new AuthenticatedPrincipal(cashierId, "operator1", "POS_OPERATOR");

        when(posSaleService.recordOnlineSale(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessRuleException("Variant is not active"));

        RecordOnlineSaleRequest request = new RecordOnlineSaleRequest(
                terminalId,
                List.of(new PosSaleLineItem(variantId, locationId, 1)),
                cashierId,
                "SGD");

        mockMvc.perform(post("/api/v1/pos/sales")
                        .with(authentication(buildAuth(operator)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should_return404_when_terminalNotFound")
    void should_return404_when_terminalNotFound() throws Exception {
        AuthenticatedPrincipal operator = new AuthenticatedPrincipal(cashierId, "operator1", "POS_OPERATOR");

        when(posSaleService.recordOnlineSale(any(), any(), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("PosTerminal", terminalId));

        RecordOnlineSaleRequest request = new RecordOnlineSaleRequest(
                terminalId,
                List.of(new PosSaleLineItem(variantId, locationId, 1)),
                cashierId,
                "SGD");

        mockMvc.perform(post("/api/v1/pos/sales")
                        .with(authentication(buildAuth(operator)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("should_return401_when_unauthenticated")
    void should_return401_when_unauthenticated() throws Exception {
        RecordOnlineSaleRequest request = new RecordOnlineSaleRequest(
                terminalId,
                List.of(new PosSaleLineItem(variantId, locationId, 1)),
                cashierId,
                "SGD");

        mockMvc.perform(post("/api/v1/pos/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return403_when_wrongRole")
    void should_return403_when_wrongRole() throws Exception {
        // CUSTOMER role should not be allowed to record a sale
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), "customer1", "CUSTOMER");

        RecordOnlineSaleRequest request = new RecordOnlineSaleRequest(
                terminalId,
                List.of(new PosSaleLineItem(variantId, locationId, 1)),
                cashierId,
                "SGD");

        mockMvc.perform(post("/api/v1/pos/sales")
                        .with(authentication(buildAuth(customer)))
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
