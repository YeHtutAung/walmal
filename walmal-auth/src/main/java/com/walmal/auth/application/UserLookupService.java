package com.walmal.auth.application;

import java.util.UUID;

/**
 * Cross-module interface for querying user identity data.
 * Used by the Order and POS modules only. Exposes the minimum set of fields
 * those consumers need — ISP compliance.
 *
 * <p>Implementations query auth_users via the auth module's own Repository bean.
 * No other module may inject UserRepository directly.</p>
 */
public interface UserLookupService {

    /**
     * Returns the username for a given user ID.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if not found
     */
    String findUsernameById(UUID userId);

    /**
     * Returns the role string (e.g. "ADMIN", "CASHIER") for a given user ID.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if not found
     */
    String findRoleById(UUID userId);

    /**
     * Returns {@code true} if the user exists and is_active = true.
     * Returns {@code false} for inactive or non-existent users.
     */
    boolean isUserActive(UUID userId);
}
