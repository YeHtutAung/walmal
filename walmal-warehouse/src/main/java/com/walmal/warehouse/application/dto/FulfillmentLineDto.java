package com.walmal.warehouse.application.dto;

import java.util.UUID;

public record FulfillmentLineDto(
        UUID id,
        UUID variantId,
        UUID locationId,
        String skuSnapshot,
        int quantityRequested,
        int quantityPicked,
        int discrepancy
) {}
