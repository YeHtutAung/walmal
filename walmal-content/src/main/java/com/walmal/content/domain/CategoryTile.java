package com.walmal.content.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A single clickable category tile in the home-page category grid.
 */
public record CategoryTile(
        @NotBlank @Size(max = 40) String label,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^/.*") String href,
        @Size(max = 512) String imageUrl) {}
