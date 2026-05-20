package com.walmal.inventory.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for reservation reads.
 */
public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        UUID variantId,
        UUID locationId,
        int quantity,
        String status,
        String conflictReason,
        Instant expiresAt,
        Instant createdAt
) {}
