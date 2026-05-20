package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.response.MovementResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Internal service interface for read-only movement history queries.
 *
 * <p>ISP: Movement history is consumed only by the inventory controller and internal reporting.
 * External modules do not need this interface.</p>
 */
public interface InventoryMovementQueryService {

    Page<MovementResponse> getMovementsByVariant(UUID variantId, Pageable pageable);

    Page<MovementResponse> getMovementsByLocation(UUID locationId, Pageable pageable);
}
