package com.walmal.auth.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published to {@code auth.exchange} with routing key {@code auth.user.deactivated}
 * after an ADMIN deactivates a user account.
 *
 * <p>Consumer intent: Notification module notifies the user; Order module cancels
 * any pending orders for this user.</p>
 */
public class UserDeactivatedEvent extends DomainEvent {

    private final UUID userId;
    private final String username;
    private final String performedBy;

    public UserDeactivatedEvent(UUID userId, String username, String performedBy) {
        super("auth.user.deactivated");
        this.userId = userId;
        this.username = username;
        this.performedBy = performedBy;
    }

    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getPerformedBy() { return performedBy; }
}
