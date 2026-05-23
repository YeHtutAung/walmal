package com.walmal.order.api.dto;

import com.walmal.order.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(
        @NotNull OrderStatus status,
        String reason
) {}
