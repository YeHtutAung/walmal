package com.walmal.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for creating a new inventory location.
 */
public record CreateLocationRequest(
        @NotBlank @Size(max = 200) String name,
        UUID externalReferenceId,
        boolean bufferLocation,
        boolean active
) {}
