package com.walmal.order.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module read API consumed by the notification module
 * (same pattern as auth's StaffNotificationQueryService).
 */
public interface GuestOrderQueryService {

    /**
     * @return the guest email for the order, or empty if the order does not
     * exist, is a registered-user order, or has no guest email recorded.
     */
    Optional<String> findGuestEmailByOrderId(UUID orderId);
}
