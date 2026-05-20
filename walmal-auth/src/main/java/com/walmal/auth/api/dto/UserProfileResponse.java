package com.walmal.auth.api.dto;

import java.util.UUID;

/**
 * Public view of an authenticated user's profile. Never exposes passwordHash.
 */
public record UserProfileResponse(
        UUID id,
        String username,
        String email,
        String role,
        boolean isActive
) {}
