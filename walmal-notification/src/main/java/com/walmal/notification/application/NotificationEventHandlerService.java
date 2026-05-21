package com.walmal.notification.application;

import com.walmal.notification.domain.NotificationType;

import java.util.UUID;

public interface NotificationEventHandlerService {

    void handleOrderConfirmed(UUID orderId, UUID userId);

    void handleOrderCancelled(UUID orderId, UUID userId, String cancellationReason);

    void handleFulfillmentShipped(UUID orderId, UUID userId, String carrier, String trackingNumber);

    void handleStockLow(UUID variantId, UUID locationId, int availableQuantity, int threshold);

    void handleUserRegistered(UUID userId, String username, String email);

    void handlePosSyncConflictResolved(UUID cancelledOrderId, UUID userId, String conflictReason);
}
