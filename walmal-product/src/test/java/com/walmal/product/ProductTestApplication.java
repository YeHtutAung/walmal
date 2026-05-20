package com.walmal.product;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

/**
 * Minimal Spring Boot entry point for walmal-product module tests.
 * Used by @WebMvcTest and @SpringBootTest to locate the @SpringBootConfiguration
 * anchor in this module. Not used in production — the real entry point is in walmal-app.
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class ProductTestApplication {
    // No main method needed — this class exists only as a @SpringBootConfiguration
    // anchor for test slices in walmal-product.
}
