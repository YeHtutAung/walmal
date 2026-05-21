package com.walmal.warehouse;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

/**
 * Minimal Spring Boot entry point for walmal-warehouse module tests.
 * Not used in production — the real entry point is in walmal-app.
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WarehouseTestApplication {
}
