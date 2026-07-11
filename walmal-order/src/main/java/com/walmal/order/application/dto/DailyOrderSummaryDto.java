package com.walmal.order.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day's bucket in an admin order timeseries. {@code orderCount} includes orders of every
 * status; {@code revenue} sums only {@code FULFILLED} orders for that day (always scale 2, even
 * when zero, so zero-filled and populated days serialize consistently within the same array).
 */
public record DailyOrderSummaryDto(LocalDate date, long orderCount, BigDecimal revenue, String currency) {}
