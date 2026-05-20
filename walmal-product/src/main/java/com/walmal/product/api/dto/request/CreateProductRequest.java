package com.walmal.product.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProductRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 300) String name,
        @NotBlank @Size(max = 300) String slug,
        String description,
        @Size(max = 150) String brand
) {}
