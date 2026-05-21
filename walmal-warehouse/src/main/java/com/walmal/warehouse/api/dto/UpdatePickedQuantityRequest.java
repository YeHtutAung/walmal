package com.walmal.warehouse.api.dto;

import jakarta.validation.constraints.Min;

public record UpdatePickedQuantityRequest(
        @Min(0) int quantityPicked
) {}
