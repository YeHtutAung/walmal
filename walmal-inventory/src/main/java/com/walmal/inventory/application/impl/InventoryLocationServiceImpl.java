package com.walmal.inventory.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.inventory.application.InventoryLocationService;
import com.walmal.inventory.api.dto.request.CreateLocationRequest;
import com.walmal.inventory.api.dto.response.LocationResponse;
import com.walmal.inventory.domain.InventoryLocation;
import com.walmal.inventory.infrastructure.InventoryLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryLocationServiceImpl implements InventoryLocationService {

    private final InventoryLocationRepository locationRepo;
    private final AuditService auditService;

    public InventoryLocationServiceImpl(InventoryLocationRepository locationRepo,
                                        AuditService auditService) {
        this.locationRepo = locationRepo;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationResponse> listLocations() {
        return locationRepo.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public LocationResponse getLocation(UUID locationId) {
        InventoryLocation location = locationRepo.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", locationId));
        return toResponse(location);
    }

    @Override
    @Transactional(readOnly = true)
    public LocationResponse getDefaultLocation() {
        return locationRepo.findByActiveTrue().stream()
                .filter(l -> !l.isBufferLocation())
                .findFirst()
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", null));
    }

    @Override
    @Transactional
    public LocationResponse createLocation(CreateLocationRequest request, String performedBy) {
        InventoryLocation location = new InventoryLocation(
                request.name(), request.externalReferenceId(),
                request.bufferLocation(), request.active());
        return toResponse(locationRepo.save(location));
    }

    @Override
    @Transactional
    public void deactivateLocation(UUID locationId, String performedBy) {
        InventoryLocation location = locationRepo.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("InventoryLocation", locationId));

        auditService.log(new AuditEntry(
                "inventory_locations",
                location.getId(),
                AuditAction.STATUS_CHANGE,
                "{\"isActive\":true}",
                "{\"isActive\":false}",
                performedBy));

        location.setActive(false);
        locationRepo.save(location);
    }

    private LocationResponse toResponse(InventoryLocation loc) {
        return new LocationResponse(
                loc.getId(), loc.getName(), loc.getExternalReferenceId(),
                loc.isBufferLocation(), loc.isActive(),
                loc.getCreatedAt(), loc.getUpdatedAt());
    }
}
