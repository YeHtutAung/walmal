package com.walmal.product.api.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateProductRequest(
        UUID categoryId,
        @Size(max = 300) String name,
        @Size(max = 300) String slug,
        String description,
        @Size(max = 150) String brand
) {}
