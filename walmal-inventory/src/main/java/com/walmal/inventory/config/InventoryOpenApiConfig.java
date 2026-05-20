package com.walmal.inventory.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc GroupedOpenApi configuration for the walmal-inventory module.
 * Groups all inventory endpoints under a single API group in the Swagger UI.
 */
@Configuration
public class InventoryOpenApiConfig {

    @Bean
    public GroupedOpenApi inventoryApi() {
        return GroupedOpenApi.builder()
                .group("inventory")
                .pathsToMatch("/api/v1/inventory/**")
                .addOpenApiCustomizer(openApi -> openApi.info(
                        new Info()
                                .title("Inventory Module API")
                                .description("Stock levels, reservations, movements, and locations")
                                .version("1.0")))
                .build();
    }
}
