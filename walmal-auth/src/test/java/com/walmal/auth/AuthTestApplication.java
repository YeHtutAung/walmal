package com.walmal.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot entry point for walmal-auth module tests.
 * Used by @WebMvcTest to locate the @SpringBootConfiguration in this module.
 * Not used in production — the real application entry point is in walmal-app.
 */
@SpringBootApplication
public class AuthTestApplication {
    // No main method needed — this class exists only as a @SpringBootConfiguration
    // anchor for @WebMvcTest and @SpringBootTest slices in walmal-auth.
}
