package com.walmal.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Top-level Spring configuration for the walmal-inventory module.
 *
 * <p>{@code @EnableScheduling} is declared here to activate the {@code ReservationExpiryJob}.
 * Verified that no other module currently declares {@code @EnableScheduling} — adding it here
 * is safe and will not conflict.</p>
 *
 * <p>{@code @EnableMethodSecurity} is already declared in {@code AuthSecurityConfig}
 * (walmal-auth). It must NOT be duplicated here.</p>
 */
@Configuration
@EnableScheduling
public class InventoryConfig {
    // Intentionally minimal — beans registered via component scan.
}
