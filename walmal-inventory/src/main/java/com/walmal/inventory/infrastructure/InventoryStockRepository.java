package com.walmal.inventory.infrastructure;

import com.walmal.inventory.domain.InventoryStock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryStock}.
 * This interface must NEVER be injected into any module other than walmal-inventory.
 *
 * <p>The {@link #decrementStockDirect} method uses a direct UPDATE with a WHERE clause
 * quantity guard — bypassing optimistic locking. This is intentional and only used in
 * the POS conflict resolution override path (ADR-4, Option C).</p>
 */
public interface InventoryStockRepository extends JpaRepository<InventoryStock, UUID> {

    Optional<InventoryStock> findByVariantIdAndLocationId(UUID variantId, UUID locationId);

    List<InventoryStock> findByVariantId(UUID variantId);

    List<InventoryStock> findByVariantIdIn(List<UUID> variantIds);

    List<InventoryStock> findByLocationId(UUID locationId);

    /** Paginated list with location eagerly loaded to avoid N+1. */
    @Query("SELECT s FROM InventoryStock s JOIN FETCH s.location")
    Page<InventoryStock> findAllWithLocation(Pageable pageable);

    /**
     * POS conflict override: atomically decrements available_quantity using a direct UPDATE
     * with a WHERE-clause quantity guard. Bypasses JPA optimistic locking intentionally.
     *
     * @return number of rows updated (1 = success, 0 = insufficient stock or row not found)
     */
    @Modifying
    @Query("""
            UPDATE InventoryStock s
               SET s.availableQuantity = s.availableQuantity - :delta,
                   s.version = s.version + 1
             WHERE s.variantId = :variantId
               AND s.location.id = :locationId
               AND s.availableQuantity >= :delta
            """)
    int decrementStockDirect(@Param("variantId") UUID variantId,
                             @Param("locationId") UUID locationId,
                             @Param("delta") int delta);
}
