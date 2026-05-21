package com.walmal.pos.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/pos/terminals}.
 *
 * @param name       display name of the terminal (e.g. "Main Store Register 1")
 * @param locationId inventory location UUID (cross-module reference)
 */
public record RegisterTerminalRequest(
        @NotBlank @Size(max = 200)
        String name,

        @NotNull
        UUID locationId
) {}
