package com.walmal.order.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc GroupedOpenApi configuration for the walmal-order module.
 * Groups all order endpoints under a single API group in the Swagger UI.
 */
@Configuration
public class OrderOpenApiConfig {

    @Bean
    public GroupedOpenApi orderApi() {
        return GroupedOpenApi.builder()
                .group("order")
                .pathsToMatch("/api/v1/orders/**")
                .addOpenApiCustomizer(openApi -> openApi.info(
                        new Info()
                                .title("Order Module API")
                                .description("Order creation, payment, fulfilment, and cancellation")
                                .version("1.0")))
                .build();
    }
}
