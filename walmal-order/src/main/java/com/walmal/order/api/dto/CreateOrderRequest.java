package com.walmal.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

import jakarta.validation.constraints.Email;

/**
 * Request body for {@code POST /api/v1/orders}.
 * For guest checkout, provide {@code guestEmail}; for authenticated checkout leave it null.
 */
public record CreateOrderRequest(
        @NotEmpty @Valid List<OrderLineItemRequest> items,
        @Valid ShippingAddressRequest shippingAddress,
        @NotBlank String currency,
        @Email String guestEmail
) {}
