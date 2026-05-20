package com.walmal.product.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ImageResponse(
        UUID id,
        UUID productId,
        UUID variantId,
        String storageKey,
        String cdnUrl,
        String altText,
        int displayOrder,
        boolean primary,
        Instant createdAt
) {}
