package com.walmal.auth.application;

import com.walmal.auth.api.dto.CreateUserRequest;
import com.walmal.auth.api.dto.LoginRequest;
import com.walmal.auth.api.dto.RegisterRequest;
import com.walmal.auth.api.dto.TokenResponse;
import com.walmal.auth.api.dto.UserProfileResponse;

import java.util.UUID;

/**
 * Internal service interface for the auth module. Only AuthController depends on this.
 * No other module may import or inject AuthService.
 *
 * <p>ISP: methods that are not used by downstream modules are not included in
 * {@link TokenValidationService} or {@link UserLookupService}.</p>
 */
public interface AuthService {

    /**
     * Validates credentials and issues an access + refresh token pair.
     *
     * @throws com.walmal.common.exception.BusinessRuleException if the user is inactive
     * @throws com.walmal.common.exception.ResourceNotFoundException if username not found
     */
    TokenResponse login(LoginRequest request);

    /**
     * Creates a new user account and issues tokens. Publishes {@code auth.user.registered}.
     *
     * @throws com.walmal.common.exception.BusinessRuleException if username or email already exists
     */
    TokenResponse register(RegisterRequest request);

    /**
     * Revokes the given refresh token by deleting its Redis key.
     * Access tokens expire naturally after 15 minutes.
     */
    void logout(String refreshToken, UUID userId);

    /**
     * Validates the opaque refresh token, issues a new token pair (rolling refresh),
     * and deletes the old Redis key.
     *
     * @throws com.walmal.common.exception.BusinessRuleException if token is missing or expired
     */
    TokenResponse refresh(String refreshToken);

    /**
     * Deactivates a user account. Writes an audit log entry BEFORE the repository update.
     * Publishes {@code auth.user.deactivated}.
     *
     * @param userId      the user to deactivate
     * @param performedBy the admin username authorising the action
     * @throws com.walmal.common.exception.ResourceNotFoundException if userId not found
     */
    void deactivateUser(UUID userId, String performedBy);

    /**
     * Returns the profile of the currently authenticated user.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if userId not found
     */
    UserProfileResponse getCurrentUser(UUID userId);

    /**
     * Admin-only: creates a user account with any role (ADMIN, STAFF, CASHIER, CUSTOMER).
     *
     * @throws com.walmal.common.exception.BusinessRuleException if username/email already exists
     *         or role is invalid
     */
    UserProfileResponse createUser(CreateUserRequest request, String performedBy);
}
