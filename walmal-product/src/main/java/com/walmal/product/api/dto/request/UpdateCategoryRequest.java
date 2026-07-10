package com.walmal.product.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing category's name and slug.
 */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 120) String slug
) {
}
