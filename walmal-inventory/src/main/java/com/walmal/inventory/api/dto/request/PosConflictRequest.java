package com.walmal.inventory.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for POS offline sync conflict resolution.
 */
public record PosConflictRequest(
        @NotNull UUID posSaleId,
        @NotNull UUID variantId,
        @NotNull UUID locationId,
        @Positive int quantity,
        @NotNull Instant posSaleTimestamp,
        UUID webOrderId   // nullable — null means no web order conflicts with this POS sale
) {}
