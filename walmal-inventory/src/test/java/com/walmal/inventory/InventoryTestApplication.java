package com.walmal.inventory;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Minimal Spring Boot entry point for walmal-inventory module tests.
 * Used by @WebMvcTest and @SpringBootTest to locate the @SpringBootConfiguration
 * anchor in this module. Not used in production — the real entry point is in walmal-app.
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
@EnableScheduling // in production this comes from InfrastructureAutoConfiguration (walmal-infrastructure), outside this test's component scan
public class InventoryTestApplication {
    // No main method needed — this class exists only as a @SpringBootConfiguration
    // anchor for test slices in walmal-inventory.
}
