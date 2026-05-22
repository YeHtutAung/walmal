package com.walmal.auth.api.dto;

/**
 * Admin-only request DTO for updating a user's role and/or active status.
 * Both fields are nullable — omit a field to leave that property unchanged.
 */
public record UpdateUserRequest(
        String role,    // nullable — if absent, role is not changed
        Boolean active  // nullable — if absent, active status is not changed
) {}
