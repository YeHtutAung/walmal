package com.walmal.product.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        UUID parentId,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
