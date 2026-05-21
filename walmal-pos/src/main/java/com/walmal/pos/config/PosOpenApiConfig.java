package com.walmal.pos.config;

import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc GroupedOpenApi configuration for the walmal-pos module.
 * Groups all POS endpoints under a single API group in the Swagger UI.
 */
@Configuration
public class PosOpenApiConfig {

    @Bean
    public GroupedOpenApi posApi() {
        return GroupedOpenApi.builder()
                .group("pos")
                .pathsToMatch("/api/v1/pos/**")
                .addOpenApiCustomizer(openApi -> openApi.info(
                        new Info()
                                .title("POS Module API")
                                .description("Terminal management, online sales, and offline sync")
                                .version("1.0")))
                .build();
    }
}
