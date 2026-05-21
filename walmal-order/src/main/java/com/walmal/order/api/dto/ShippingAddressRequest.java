package com.walmal.order.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for the shipping address in a {@link CreateOrderRequest}.
 */
public record ShippingAddressRequest(
        @NotBlank String line1,
        String line2,
        @NotBlank String city,
        @NotBlank String country,
        @NotBlank String postalCode
) {}
