package com.walmal.auth.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Core identity entity. Owned exclusively by walmal-auth.
 * No other module may JOIN to auth_users or declare a FK to this table.
 *
 * <p>id, createdAt, updatedAt are inherited from {@link BaseEntity}.
 * updated_at is maintained by the @PreUpdate lifecycle callback in BaseEntity;
 * it is NOT managed by a database trigger.</p>
 */
@Entity
@Table(name = "auth_users")
public class User extends BaseEntity {

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    // ── JPA required no-arg constructor ──────────────────────────────────────
    protected User() {}

    // ── Factory constructor ───────────────────────────────────────────────────
    public User(String username, String email, String passwordHash, Role role) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.isActive = true;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isActive() { return isActive; }

    // ── Setters (limited — only mutable fields) ───────────────────────────────
    public void setActive(boolean active) { isActive = active; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
