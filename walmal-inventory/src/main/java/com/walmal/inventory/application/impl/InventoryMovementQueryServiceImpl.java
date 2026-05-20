package com.walmal.inventory.application.impl;

import com.walmal.inventory.application.InventoryMovementQueryService;
import com.walmal.inventory.api.dto.response.MovementResponse;
import com.walmal.inventory.infrastructure.InventoryMovementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryMovementQueryServiceImpl implements InventoryMovementQueryService {

    private final InventoryMovementRepository movementRepo;

    public InventoryMovementQueryServiceImpl(InventoryMovementRepository movementRepo) {
        this.movementRepo = movementRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MovementResponse> getMovementsByVariant(UUID variantId, Pageable pageable) {
        return movementRepo.findByVariantId(variantId, pageable).map(m -> new MovementResponse(
                m.getId(),
                m.getVariantId(),
                m.getLocation().getId(),
                m.getLocation().getName(),
                m.getMovementType().name(),
                m.getQuantityDelta(),
                m.getReferenceId(),
                m.getPerformedBy(),
                m.getCreatedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MovementResponse> getMovementsByLocation(UUID locationId, Pageable pageable) {
        return movementRepo.findByLocationId(locationId, pageable).map(m -> new MovementResponse(
                m.getId(),
                m.getVariantId(),
                m.getLocation().getId(),
                m.getLocation().getName(),
                m.getMovementType().name(),
                m.getQuantityDelta(),
                m.getReferenceId(),
                m.getPerformedBy(),
                m.getCreatedAt()));
    }
}
