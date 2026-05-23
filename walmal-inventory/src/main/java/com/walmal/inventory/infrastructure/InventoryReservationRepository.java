package com.walmal.inventory.infrastructure;

import com.walmal.inventory.domain.InventoryReservation;
import com.walmal.inventory.domain.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryReservation}.
 * This interface must NEVER be injected into any module other than walmal-inventory.
 */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderId(UUID orderId);

    List<InventoryReservation> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    Optional<InventoryReservation> findByOrderIdAndVariantIdAndStatus(
            UUID orderId, UUID variantId, ReservationStatus status);

    /** Paginated list filtered by status. */
    Page<InventoryReservation> findByStatus(ReservationStatus status, Pageable pageable);

    /**
     * Finds all PENDING reservations whose expiry time has passed.
     * Used by {@code ReservationExpiryJob}.
     * Relies on idx_inv_reservations_pending_expiry partial index — do NOT drop that index.
     */
    @Query("SELECT r FROM InventoryReservation r WHERE r.status = 'PENDING' AND r.expiresAt < :now")
    List<InventoryReservation> findExpiredReservations(@Param("now") Instant now);
}
