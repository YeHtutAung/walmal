package com.walmal.order.config;

import org.springframework.context.annotation.Configuration;

/**
 * Top-level Spring configuration for the walmal-order module.
 *
 * <p>Intentionally minimal — beans are registered via component scan.
 * {@code @EnableMethodSecurity} is already declared in {@code AuthSecurityConfig}
 * (walmal-auth) and must NOT be duplicated here.</p>
 */
@Configuration
public class OrderConfig {
    // Intentionally minimal — beans registered via component scan.
}
