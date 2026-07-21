package com.walmal.content.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A promotional feature block with a single call-to-action.
 */
public record Promo(
        @Size(max = 60) String eyebrow,
        @NotBlank @Size(max = 120) String heading,
        @Size(max = 400) String text,
        @Valid @NotNull Cta cta,
        @Size(max = 512) String imageUrl) {}
