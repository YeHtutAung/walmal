package com.walmal.inventory.infrastructure;

import com.walmal.inventory.domain.InventoryMovement;
import com.walmal.inventory.domain.MovementType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryMovement}.
 * This interface must NEVER be injected into any module other than walmal-inventory.
 *
 * <p>IMPORTANT: This repository intentionally exposes NO delete method. The parent
 * {@code JpaRepository.delete*} methods are still technically inherited but must never
 * be called. {@code InventoryMovement} is an append-only audit trail.</p>
 */
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {

    Page<InventoryMovement> findByVariantId(UUID variantId, Pageable pageable);

    @Query("SELECT m FROM InventoryMovement m WHERE m.location.id = :locationId")
    Page<InventoryMovement> findByLocationId(@Param("locationId") UUID locationId, Pageable pageable);

    List<InventoryMovement> findByReferenceId(UUID referenceId);

    Optional<InventoryMovement> findByReferenceIdAndMovementType(
            UUID referenceId, MovementType movementType);
}
