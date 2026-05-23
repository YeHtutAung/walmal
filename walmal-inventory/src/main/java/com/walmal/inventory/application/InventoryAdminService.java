package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.response.ReservationResponse;
import com.walmal.inventory.api.dto.response.StockListItemResponse;
import com.walmal.inventory.domain.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.lang.Nullable;

/**
 * Admin-facing read operations for inventory management UI.
 *
 * <p>ISP: separated from {@link InventoryQueryService} (consumed by POS)
 * because POS has no use for paginated admin list operations.</p>
 *
 * <p>Not exposed cross-module — used only by this module's REST controllers.</p>
 */
public interface InventoryAdminService {

    /**
     * Paginated stock level list across all locations.
     * Location name is eagerly loaded to avoid N+1.
     */
    Page<StockListItemResponse> listAllStock(Pageable pageable);

    /**
     * Paginated reservation list, optionally filtered by status.
     *
     * @param status null → returns all statuses
     */
    Page<ReservationResponse> listReservations(@Nullable ReservationStatus status, Pageable pageable);
}
