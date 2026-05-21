package com.walmal.pos.infrastructure;

import com.walmal.pos.domain.PosSale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * JPA repository for {@link PosSale} — owned exclusively by walmal-pos.
 * Must NEVER be injected into any bean outside the {@code com.walmal.pos} package.
 */
public interface PosSaleRepository extends JpaRepository<PosSale, UUID> {

    /**
     * Returns a paginated list of sales for a terminal, ordered by the pageable sort.
     */
    Page<PosSale> findByTerminalId(UUID terminalId, Pageable pageable);
}
