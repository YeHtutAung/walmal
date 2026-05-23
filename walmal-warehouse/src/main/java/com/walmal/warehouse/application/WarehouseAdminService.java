package com.walmal.warehouse.application;

import com.walmal.warehouse.application.dto.FulfillmentDetailDto;
import com.walmal.warehouse.application.dto.FulfillmentSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Admin read-only view of warehouse fulfillments for the admin panel.
 * Segregated from {@link WarehouseFulfillmentService} (operator lifecycle concerns).
 */
public interface WarehouseAdminService {

    Page<FulfillmentSummaryDto> listFulfillments(Pageable pageable);

    FulfillmentDetailDto getFulfillmentById(UUID fulfillmentId);
}
