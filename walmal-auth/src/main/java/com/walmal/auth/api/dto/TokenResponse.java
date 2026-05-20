package com.walmal.auth.api.dto;

/**
 * Returned by login, register, and refresh endpoints.
 *
 * @param accessToken  signed HS256 JWT (15-minute TTL)
 * @param refreshToken opaque UUID stored in Redis (7-day TTL, rolling)
 * @param tokenType    always "Bearer"
 * @param expiresIn    access token TTL in seconds
 * @param role         the authenticated user's role string
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String role
) {
    public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn, String role) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, role);
    }
}
