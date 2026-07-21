package com.walmal.content.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The hero banner at the top of the home page. Renders one or two CTA buttons —
 * {@code secondaryCta} is optional.
 */
public record Hero(
        @Size(max = 60) String eyebrow,
        @NotBlank @Size(max = 120) String headline,
        @Size(max = 400) String subtext,
        @Valid @NotNull Cta primaryCta,
        @Valid Cta secondaryCta,            // nullable — hero renders 1 or 2 buttons
        @Size(max = 512) String imageUrl) {}
