package com.walmal.auth.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published to {@code auth.exchange} with routing key {@code auth.user.registered}
 * after a user account is successfully persisted.
 *
 * <p>Consumer intent: Notification module sends a welcome email.</p>
 */
public class UserRegisteredEvent extends DomainEvent {

    private final UUID userId;
    private final String username;
    private final String email;
    private final String role;

    public UserRegisteredEvent(UUID userId, String username, String email, String role) {
        super("auth.user.registered");
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.role = role;
    }

    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
}
