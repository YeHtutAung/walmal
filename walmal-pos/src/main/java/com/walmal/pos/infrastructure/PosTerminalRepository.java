package com.walmal.pos.infrastructure;

import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.TerminalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * JPA repository for {@link PosTerminal} — owned exclusively by walmal-pos.
 * Must NEVER be injected into any bean outside the {@code com.walmal.pos} package.
 */
public interface PosTerminalRepository extends JpaRepository<PosTerminal, UUID> {

    /**
     * Finds all terminals at a given inventory location with the specified status.
     * Used by store management dashboard queries.
     */
    List<PosTerminal> findByLocationIdAndStatus(UUID locationId, TerminalStatus status);
}
