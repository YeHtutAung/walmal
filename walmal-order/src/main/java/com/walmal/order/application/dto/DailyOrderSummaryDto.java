package com.walmal.order.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyOrderSummaryDto(LocalDate date, long orderCount, BigDecimal revenue, String currency) {}
