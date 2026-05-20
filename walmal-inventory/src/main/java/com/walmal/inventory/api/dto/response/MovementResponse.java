package com.walmal.inventory.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for inventory movement reads.
 */
public record MovementResponse(
        UUID movementId,
        UUID variantId,
        UUID locationId,
        String locationName,
        String movementType,
        int quantityDelta,
        UUID referenceId,
        String performedBy,
        Instant createdAt
) {}
