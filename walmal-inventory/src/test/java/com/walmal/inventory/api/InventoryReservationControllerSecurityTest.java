package com.walmal.inventory.api;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.inventory.application.ConflictOutcome;
import com.walmal.inventory.application.ConflictResolutionResult;
import com.walmal.inventory.application.InventoryAdminService;
import com.walmal.inventory.application.InventoryReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization tests for {@link InventoryReservationController}'s stock-lifecycle
 * mutations (confirm / release / resolve-conflict). These endpoints are ops-only:
 * in-process, other modules call the {@link InventoryReservationService} interface
 * directly; over HTTP they must be reachable only by ADMIN / WAREHOUSE_MANAGER, not
 * by any authenticated customer (regression guard for the IDOR fix).
 */
@WebMvcTest(controllers = InventoryReservationController.class)
@Import({AuthSecurityConfig.class, InventoryExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class InventoryReservationControllerSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean InventoryReservationService reservationService;
    @MockitoBean InventoryAdminService adminService;
    @MockitoBean TokenValidationService tokenValidationService;

    private static final String CONFLICT_BODY = """
            {"posSaleId":"%s","variantId":"%s","locationId":"%s",
             "quantity":1,"posSaleTimestamp":"2026-07-17T00:00:00Z","webOrderId":null}
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    // ── confirm ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_customerConfirmsReservation")
    void should_return403_when_customerConfirmsReservation() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{orderId}/confirm", UUID.randomUUID())
                        .with(authentication(buildAuth(customer()))))
                .andExpect(status().isForbidden());
        verify(reservationService, never()).confirmReservation(any());
    }

    @Test
    @DisplayName("should_return204_when_warehouseManagerConfirmsReservation")
    void should_return204_when_warehouseManagerConfirmsReservation() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{orderId}/confirm", UUID.randomUUID())
                        .with(authentication(buildAuth(role("WAREHOUSE_MANAGER")))))
                .andExpect(status().isNoContent());
    }

    // ── release ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_customerReleasesReservation")
    void should_return403_when_customerReleasesReservation() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{orderId}/release", UUID.randomUUID())
                        .with(authentication(buildAuth(customer()))))
                .andExpect(status().isForbidden());
        verify(reservationService, never()).releaseReservation(any(), any());
    }

    @Test
    @DisplayName("should_return204_when_adminReleasesReservation")
    void should_return204_when_adminReleasesReservation() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{orderId}/release", UUID.randomUUID())
                        .with(authentication(buildAuth(role("ADMIN")))))
                .andExpect(status().isNoContent());
    }

    // ── resolve-conflict ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_customerResolvesConflict")
    void should_return403_when_customerResolvesConflict() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/resolve-conflict")
                        .contentType("application/json").content(CONFLICT_BODY)
                        .with(authentication(buildAuth(customer()))))
                .andExpect(status().isForbidden());
        verify(reservationService, never()).resolveConflict(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("should_return200_when_adminResolvesConflict")
    void should_return200_when_adminResolvesConflict() throws Exception {
        when(reservationService.resolveConflict(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new ConflictResolutionResult(ConflictOutcome.NO_CONFLICT, UUID.randomUUID(), null));

        mockMvc.perform(post("/api/v1/inventory/reservations/resolve-conflict")
                        .contentType("application/json").content(CONFLICT_BODY)
                        .with(authentication(buildAuth(role("ADMIN")))))
                .andExpect(status().isOk());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static AuthenticatedPrincipal customer() {
        return new AuthenticatedPrincipal(UUID.randomUUID(), "customer1", "CUSTOMER");
    }

    private static AuthenticatedPrincipal role(String role) {
        return new AuthenticatedPrincipal(UUID.randomUUID(), role.toLowerCase(), role);
    }

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }
}
