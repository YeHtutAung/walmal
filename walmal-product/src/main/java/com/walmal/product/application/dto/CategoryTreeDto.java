package com.walmal.product.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * Category tree node — recursive structure for full category hierarchy display.
 */
public record CategoryTreeDto(
        UUID categoryId,
        String name,
        String slug,
        boolean active,
        List<CategoryTreeDto> children
) {}
