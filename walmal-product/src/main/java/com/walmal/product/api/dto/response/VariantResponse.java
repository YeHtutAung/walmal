package com.walmal.product.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record VariantResponse(
        UUID id,
        UUID productId,
        String sku,
        String name,
        String barcode,
        Map<String, Object> attributes,
        String status,
        BigDecimal currentPrice,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {}
