package com.walmal.pos.api.dto;

import com.walmal.pos.application.dto.OfflineSalePayload;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/pos/sync}.
 *
 * <p>Maximum batch size is 100 items (configurable via {@code pos.sync.max-batch-size}).
 * Submitting more than 100 items will result in a 400 Bad Request response.
 * This limit exists to mitigate HTTP timeout risk for large offline batches (ADR-6 Risk 2).</p>
 *
 * @param terminalId   the submitting terminal UUID
 * @param offlineSales list of offline sale payloads (max 100 items)
 */
public record OfflineSyncRequest(
        @NotNull
        UUID terminalId,

        @NotEmpty @Size(max = 100, message = "Sync batch cannot exceed 100 items")
        List<OfflineSalePayload> offlineSales
) {}
