package com.walmal.warehouse.api.dto;

import com.walmal.warehouse.domain.FulfillmentStatus;
import jakarta.validation.constraints.NotNull;

public record AdvanceStatusRequest(
        @NotNull FulfillmentStatus targetStatus,
        String notes
) {}
