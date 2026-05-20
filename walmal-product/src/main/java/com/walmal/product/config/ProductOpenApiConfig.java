package com.walmal.product.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI configuration for the walmal-product module.
 *
 * <p>Exposes a grouped API definition covering all {@code /api/v1/product/**} endpoints.
 * The Swagger UI will include a "Product" group alongside the "Authentication" group
 * from walmal-auth.</p>
 */
@Configuration
public class ProductOpenApiConfig {

    @Bean
    public GroupedOpenApi productOpenApi() {
        return GroupedOpenApi.builder()
                .group("product")
                .displayName("Product Module")
                .pathsToMatch("/api/v1/product/**")
                .build();
    }
}
