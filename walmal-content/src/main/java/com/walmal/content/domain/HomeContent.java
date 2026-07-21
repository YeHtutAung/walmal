package com.walmal.content.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The full editorial home-page document. Persisted as a single JSONB value in
 * {@code content_home.content} and later reused as the API request/response body.
 */
public record HomeContent(
        @Valid @NotNull Hero hero,
        @Valid @Size(max = 12) List<CategoryTile> categoryTiles,   // 0..12, order = display order
        @Valid @NotNull Promo promo) {}
