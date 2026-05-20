package com.walmal.auth.domain;

/**
 * User roles within the walmal platform.
 * Stored as VARCHAR in auth_users.role with a CHECK constraint.
 */
public enum Role {
    ADMIN,
    STAFF,
    CASHIER,
    CUSTOMER
}
