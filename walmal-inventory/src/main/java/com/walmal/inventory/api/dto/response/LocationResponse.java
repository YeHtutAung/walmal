package com.walmal.inventory.api.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for inventory location reads.
 */
public record LocationResponse(
        UUID id,
        String name,
        UUID externalReferenceId,
        boolean bufferLocation,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
