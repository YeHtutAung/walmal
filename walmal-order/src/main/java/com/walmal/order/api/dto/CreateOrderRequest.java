package com.walmal.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/orders}.
 */
public record CreateOrderRequest(
        @NotEmpty @Valid List<OrderLineItemRequest> items,
        @Valid ShippingAddressRequest shippingAddress,
        @NotBlank String currency
) {}
