package com.walmal.content.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A call-to-action button: a display label and a site-relative link target.
 */
public record Cta(
        @NotBlank @Size(max = 40) String label,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^/.*", message = "href must be a site-relative path starting with /")
        String href) {}
