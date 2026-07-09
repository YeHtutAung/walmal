package com.walmal.inventory.application;

import com.walmal.inventory.api.dto.request.CreateLocationRequest;
import com.walmal.inventory.api.dto.response.LocationResponse;

import java.util.List;
import java.util.UUID;

/**
 * Internal service interface for inventory location management.
 *
 * <p>ISP: This interface is consumed only within walmal-inventory (by its own controller).
 * External modules reference locations by UUID — they do not need CRUD operations.</p>
 */
public interface InventoryLocationService {

    List<LocationResponse> listLocations();

    LocationResponse getLocation(UUID locationId);

    /** Returns the first active, non-buffer location; accessible without authentication. */
    LocationResponse getDefaultLocation();

    LocationResponse createLocation(CreateLocationRequest request, String performedBy);

    /**
     * Deactivates a location. Writes to audit_log BEFORE the status update.
     *
     * @param locationId  the location to deactivate
     * @param performedBy username of the admin performing the action
     */
    void deactivateLocation(UUID locationId, String performedBy);
}
