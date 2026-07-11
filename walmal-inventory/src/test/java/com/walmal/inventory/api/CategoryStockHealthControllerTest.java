package com.walmal.inventory.api;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.inventory.api.dto.response.CategoryStockHealthDto;
import com.walmal.inventory.application.CategoryStockHealthService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link CategoryStockHealthController}.
 * Imports AuthSecurityConfig to exercise the full security filter chain.
 */
@WebMvcTest(controllers = CategoryStockHealthController.class)
@Import({AuthSecurityConfig.class, InventoryExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class CategoryStockHealthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean CategoryStockHealthService categoryStockHealthService;
    @MockitoBean TokenValidationService tokenValidationService;

    @Test
    @DisplayName("should_return401_when_unauthenticated")
    void should_return401_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/categories/stock-health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("should_return200AndCategoryList_when_adminRequestsStockHealth")
    void should_return200AndCategoryList_when_adminRequestsStockHealth() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(UUID.randomUUID(), "admin", "ADMIN");
        when(categoryStockHealthService.getStockHealthByCategory()).thenReturn(List.of(
                new CategoryStockHealthDto(UUID.randomUUID(), "Electronics", 5, 4, 1, 0)
        ));

        mockMvc.perform(get("/api/v1/inventory/categories/stock-health")
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].productCount").value(5));
    }

    @Test
    @DisplayName("should_return200AndCategoryList_when_warehouseManagerRequestsStockHealth")
    void should_return200AndCategoryList_when_warehouseManagerRequestsStockHealth() throws Exception {
        AuthenticatedPrincipal manager = new AuthenticatedPrincipal(UUID.randomUUID(), "manager1", "WAREHOUSE_MANAGER");
        when(categoryStockHealthService.getStockHealthByCategory()).thenReturn(List.of(
                new CategoryStockHealthDto(UUID.randomUUID(), "Electronics", 5, 4, 1, 0)
        ));

        mockMvc.perform(get("/api/v1/inventory/categories/stock-health")
                        .with(authentication(buildAuth(manager))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("should_return403_when_customerRequestsStockHealth")
    void should_return403_when_customerRequestsStockHealth() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(UUID.randomUUID(), "cust", "CUSTOMER");

        mockMvc.perform(get("/api/v1/inventory/categories/stock-health")
                        .with(authentication(buildAuth(customer))))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }
}
