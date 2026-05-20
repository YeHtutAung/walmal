package com.walmal.common.auth;

import java.util.UUID;

/**
 * Immutable principal record populated by JwtAuthenticationFilter and placed in
 * the Spring SecurityContextHolder. Used by all downstream modules via
 * {@code @AuthenticationPrincipal AuthenticatedPrincipal} — no walmal-auth import required.
 *
 * <p>Role is stored as a plain String so that walmal-common does not depend on
 * walmal-auth's Role enum. Consumers that need enum semantics must parse the string.</p>
 */
public record AuthenticatedPrincipal(UUID userId, String username, String role) {}
