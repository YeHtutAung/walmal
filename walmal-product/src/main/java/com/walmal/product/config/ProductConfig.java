package com.walmal.product.config;

import org.springframework.context.annotation.Configuration;

/**
 * Top-level Spring configuration for the walmal-product module.
 *
 * <p>Method security ({@code @PreAuthorize}) is already enabled by
 * {@code AuthSecurityConfig} via {@code @EnableMethodSecurity}. Duplicating
 * {@code @EnableMethodSecurity} here would cause an error in some Spring Boot versions
 * and is unnecessary.</p>
 *
 * <p>All beans in this module are registered through component scanning
 * ({@code @Service}, {@code @Repository}, {@code @Component}). No manual bean
 * registration is required in this class for MVP.</p>
 */
@Configuration
public class ProductConfig {
    // Intentionally minimal — beans registered via component scan.
}
