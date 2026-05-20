package com.walmal.inventory.infrastructure;

import com.walmal.inventory.domain.InventoryLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link InventoryLocation}.
 * This interface must NEVER be injected into any module other than walmal-inventory.
 */
public interface InventoryLocationRepository extends JpaRepository<InventoryLocation, UUID> {

    List<InventoryLocation> findByBufferLocationTrueAndActiveTrue();

    Optional<InventoryLocation> findByExternalReferenceId(UUID externalReferenceId);

    List<InventoryLocation> findByActiveTrue();
}
