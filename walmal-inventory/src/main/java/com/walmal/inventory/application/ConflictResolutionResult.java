package com.walmal.inventory.application;

import java.util.UUID;

/**
 * Result returned by {@link InventoryReservationService#resolveConflict}.
 *
 * @param outcome       the resolution outcome — drives POS sync_status and event selection
 * @param locationUsed  the inventory location from which stock was deducted (null if STOCK_UNAVAILABLE)
 * @param cancelledOrderId the web order UUID that was cancelled, if any (null for NO_CONFLICT / STOCK_UNAVAILABLE)
 */
public record ConflictResolutionResult(
        ConflictOutcome outcome,
        UUID locationUsed,
        UUID cancelledOrderId
) {
    public static ConflictResolutionResult noConflict(UUID locationUsed) {
        return new ConflictResolutionResult(ConflictOutcome.NO_CONFLICT, locationUsed, null);
    }

    public static ConflictResolutionResult posPriority(UUID locationUsed, UUID cancelledOrderId) {
        return new ConflictResolutionResult(ConflictOutcome.POS_PRIORITY, locationUsed, cancelledOrderId);
    }

    public static ConflictResolutionResult bufferExhausted(UUID cancelledOrderId) {
        return new ConflictResolutionResult(ConflictOutcome.BUFFER_EXHAUSTED, null, cancelledOrderId);
    }

    public static ConflictResolutionResult stockUnavailable() {
        return new ConflictResolutionResult(ConflictOutcome.STOCK_UNAVAILABLE, null, null);
    }
}
