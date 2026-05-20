package com.walmal.product.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 120) String slug,
        UUID parentId
) {}
