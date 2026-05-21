package com.walmal.pos.infrastructure;

import com.walmal.pos.domain.PosSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * JPA repository for {@link PosSaleItem} — owned exclusively by walmal-pos.
 * Insert-only access pattern: {@code save()} is called only at sale creation time.
 * Must NEVER be injected into any bean outside the {@code com.walmal.pos} package.
 */
public interface PosSaleItemRepository extends JpaRepository<PosSaleItem, UUID> {

    /**
     * Returns all line items for a given sale.
     */
    List<PosSaleItem> findBySaleId(UUID saleId);
}
