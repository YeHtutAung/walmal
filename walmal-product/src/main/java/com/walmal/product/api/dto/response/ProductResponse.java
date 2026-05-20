package com.walmal.product.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID categoryId,
        String name,
        String slug,
        String description,
        String brand,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
