package com.walmal.inventory.config;

import org.springframework.context.annotation.Configuration;

/**
 * Top-level Spring configuration for the walmal-inventory module.
 *
 * <p>{@code @EnableScheduling} (which activates {@code ReservationExpiryJob})
 * is declared once, in {@code InfrastructureAutoConfiguration}
 * (walmal-infrastructure) — it must NOT be duplicated here.</p>
 *
 * <p>{@code @EnableMethodSecurity} is already declared in {@code AuthSecurityConfig}
 * (walmal-auth). It must NOT be duplicated here.</p>
 */
@Configuration
public class InventoryConfig {
    // Intentionally minimal — beans registered via component scan.
}
