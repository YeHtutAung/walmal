package com.walmal.order.application.dto;

import com.walmal.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight JPQL projection — do not add fields beyond what the daily-summary query needs. */
public record OrderTimeseriesRow(Instant createdAt, BigDecimal totalAmount, String currency, OrderStatus status) {}
