package com.walmal.product.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.product.api.dto.request.CreateProductRequest;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductManagementService;
import com.walmal.product.application.ProductSearchService;
import com.walmal.product.application.dto.CategoryTreeDto;
import com.walmal.product.application.dto.ProductDetailDto;
import com.walmal.product.application.dto.ProductSummaryDto;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest for {@link ProductController}.
 *
 * <p>Imports {@code AuthSecurityConfig} so the full security filter chain (JWT validation,
 * role checks) is exercised. Services are mocked.</p>
 */
@WebMvcTest(controllers = ProductController.class)
@Import({AuthSecurityConfig.class, ProductExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-controller-tests-padding",
        "walmal.jwt.access-token-expire-minutes=15"
})
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ProductManagementService managementService;
    @MockitoBean ProductSearchService searchService;
    @MockitoBean ProductCatalogService catalogService;
    @MockitoBean TokenValidationService tokenValidationService;

    // ── GET /categories — requires auth per AuthSecurityConfig anyRequest().authenticated() ──

    @Test
    @DisplayName("should_return200WithCategoryList_when_getCategoryTree")
    @WithMockUser(username = "customer1", roles = "CUSTOMER")
    void should_return200WithCategoryList_when_getCategoryTree() throws Exception {
        List<CategoryTreeDto> tree = List.of(
                new CategoryTreeDto(UUID.randomUUID(), "Electronics", "electronics", true, List.of()),
                new CategoryTreeDto(UUID.randomUUID(), "Apparel", "apparel", true, List.of()));

        when(searchService.getCategoryTree()).thenReturn(tree);

        mockMvc.perform(get("/api/v1/product/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("Electronics"));
    }

    // ── GET /search ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200WithPaginatedResults_when_searchProducts")
    @WithMockUser(username = "customer1", roles = "CUSTOMER")
    void should_return200WithPaginatedResults_when_searchProducts() throws Exception {
        ProductSummaryDto dto = new ProductSummaryDto(
                UUID.randomUUID(), "Test Widget", "test-widget",
                "TestBrand", null, new BigDecimal("19.99"), "USD");
        Page<ProductSummaryDto> page = new PageImpl<>(List.of(dto));

        when(searchService.searchProducts(anyString(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/product/search").param("q", "widget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].name").value("Test Widget"));
    }

    // ── GET /{productId} ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return200WithProductDetail_when_productExists")
    @WithMockUser(username = "customer1", roles = "CUSTOMER")
    void should_return200WithProductDetail_when_productExists() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        ProductDetailDto dto = new ProductDetailDto(
                productId, "Test Product", "test-product", "TestBrand",
                "A description", "ACTIVE", categoryId, "Electronics", null, null, null);

        when(catalogService.getProductDetails(productId)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/product/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Test Product"))
                .andExpect(jsonPath("$.data.categoryId").value(categoryId.toString()));
    }

    @Test
    @DisplayName("should_return404_when_productNotFound")
    @WithMockUser(username = "customer1", roles = "CUSTOMER")
    void should_return404_when_productNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(catalogService.getProductDetails(unknownId))
                .thenThrow(new ResourceNotFoundException("Product", unknownId));

        mockMvc.perform(get("/api/v1/product/{productId}", unknownId))
                .andExpect(status().isNotFound());
    }

    // ── POST / (create product) ───────────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_createProductCalledByCustomer")
    void should_return403_when_createProductCalledByCustomer() throws Exception {
        AuthenticatedPrincipal customer = new AuthenticatedPrincipal(
                UUID.randomUUID(), "customer1", "CUSTOMER");

        CreateProductRequest request = new CreateProductRequest(
                UUID.randomUUID(), "New Product", "new-product", null, null);

        mockMvc.perform(post("/api/v1/product")
                        .with(authentication(buildAuth(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return201_when_createProductCalledByAdmin")
    void should_return201_when_createProductCalledByAdmin() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(
                UUID.randomUUID(), "admin", "ADMIN");

        UUID categoryId = UUID.randomUUID();
        CreateProductRequest request = new CreateProductRequest(
                categoryId, "New Product", "new-product", null, null);

        ProductDetailDto created = new ProductDetailDto(
                UUID.randomUUID(), "New Product", "new-product", null, null, "ACTIVE", categoryId, "Electronics", null, null, null);

        when(managementService.createProduct(any(), anyString())).thenReturn(created);

        mockMvc.perform(post("/api/v1/product")
                        .with(authentication(buildAuth(admin)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("New Product"));
    }

    @Test
    @DisplayName("should_return201_when_createProductCalledByStaff")
    void should_return201_when_createProductCalledByStaff() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(
                UUID.randomUUID(), "staff1", "STAFF");

        CreateProductRequest request = new CreateProductRequest(
                UUID.randomUUID(), "Staff Product", "staff-product", null, null);

        ProductDetailDto created = new ProductDetailDto(
                UUID.randomUUID(), "Staff Product", "staff-product", null, null, "ACTIVE", null, "TestCat", null, null, null);

        when(managementService.createProduct(any(), anyString())).thenReturn(created);

        mockMvc.perform(post("/api/v1/product")
                        .with(authentication(buildAuth(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    // ── POST /{productId}/deactivate ──────────────────────────────────────────

    @Test
    @DisplayName("should_return403_when_deactivateProductCalledByStaff")
    void should_return403_when_deactivateProductCalledByStaff() throws Exception {
        AuthenticatedPrincipal staff = new AuthenticatedPrincipal(
                UUID.randomUUID(), "staff1", "STAFF");

        mockMvc.perform(post("/api/v1/product/{productId}/deactivate", UUID.randomUUID())
                        .with(authentication(buildAuth(staff))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("should_return204_when_deactivateProductCalledByAdmin")
    void should_return204_when_deactivateProductCalledByAdmin() throws Exception {
        AuthenticatedPrincipal admin = new AuthenticatedPrincipal(
                UUID.randomUUID(), "admin", "ADMIN");

        mockMvc.perform(post("/api/v1/product/{productId}/deactivate", UUID.randomUUID())
                        .with(authentication(buildAuth(admin))))
                .andExpect(status().isNoContent());
    }

    // ── Unauthenticated access ────────────────────────────────────────────────

    @Test
    @DisplayName("should_return401_when_createProductCalledWithoutAuthentication")
    void should_return401_when_createProductCalledWithoutAuthentication() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                UUID.randomUUID(), "Anon Product", "anon-product", null, null);

        mockMvc.perform(post("/api/v1/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken buildAuth(AuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }
}
