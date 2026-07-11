package com.walmal.order.application.impl;

import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderTimeseriesRow;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.infrastructure.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Follows this codebase's established service-impl unit test convention (confirmed via
// ProductManagementServiceImplTest, ProductCatalogServiceImplTest, NotificationServiceImplTest,
// AuthServiceImplTest, InventoryQueryServiceImplTest, PosSyncServiceImplTest): MockitoExtension +
// @Mock fields wired manually in @BeforeEach, not a hand-rolled `new ...(Mockito.mock(...), ...)`
// in a field initializer.
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceDailySummaryTest {

    @Mock private OrderRepository orderRepository;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private AuditService auditService;

    private OrderAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderAdminServiceImpl(orderRepository, eventPublisher, auditService);
    }

    private static Instant onDate(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).plusHours(6).toInstant(); // mid-morning UTC
    }

    @Test
    void returns30ZeroFilledDays_whenNoOrders() {
        LocalDate end = LocalDate.of(2026, 7, 11);
        List<DailyOrderSummaryDto> result = service.buildDailySummary(List.of(), end);

        assertThat(result).hasSize(30);
        assertThat(result.get(0).date()).isEqualTo(end.minusDays(29));
        assertThat(result.get(29).date()).isEqualTo(end);
        assertThat(result).allMatch(d -> d.orderCount() == 0 && d.revenue().compareTo(BigDecimal.ZERO) == 0);
        assertThat(result.get(0).currency()).isEqualTo("USD");
        // Zero-order days must serialize with the same scale as populated days (both scale 2),
        // since zero-filled and populated buckets always sit side by side in the response array.
        assertThat(result).allMatch(d -> d.revenue().scale() == 2);
    }

    @Test
    void zeroFilledDayRevenueScale_matchesPopulatedDayRevenueScale() {
        LocalDate populatedDay = LocalDate.of(2026, 7, 10);
        LocalDate end = LocalDate.of(2026, 7, 11);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(populatedDay), new BigDecimal("100.00"), "USD", OrderStatus.FULFILLED)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, end);
        DailyOrderSummaryDto populatedBucket = result.stream()
                .filter(d -> d.date().equals(populatedDay)).findFirst().orElseThrow();
        DailyOrderSummaryDto zeroBucket = result.stream()
                .filter(d -> d.date().equals(end)).findFirst().orElseThrow();

        assertThat(zeroBucket.orderCount()).isZero();
        assertThat(zeroBucket.revenue().scale()).isEqualTo(populatedBucket.revenue().scale());
        assertThat(zeroBucket.revenue().scale()).isEqualTo(2);
    }

    @Test
    void countsAllStatuses_butOnlySumsFulfilledRevenue() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("100.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "USD", OrderStatus.PENDING),
                new OrderTimeseriesRow(onDate(day), new BigDecimal("25.00"), "USD", OrderStatus.CANCELLED)
        );

        DailyOrderSummaryDto bucket = service.buildDailySummary(rows, day).stream()
                .filter(d -> d.date().equals(day)).findFirst().orElseThrow();

        assertThat(bucket.orderCount()).isEqualTo(3);
        assertThat(bucket.revenue()).isEqualByComparingTo("100.00");
    }

    @Test
    void groupsMultipleOrdersOnSameDay_andSeparatesDifferentDays() {
        LocalDate d1 = LocalDate.of(2026, 7, 9);
        LocalDate d2 = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(d1), new BigDecimal("10.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(d1), new BigDecimal("20.00"), "USD", OrderStatus.FULFILLED),
                new OrderTimeseriesRow(onDate(d2), new BigDecimal("5.00"), "USD", OrderStatus.FULFILLED)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, d2);
        DailyOrderSummaryDto bucket1 = result.stream().filter(d -> d.date().equals(d1)).findFirst().orElseThrow();
        DailyOrderSummaryDto bucket2 = result.stream().filter(d -> d.date().equals(d2)).findFirst().orElseThrow();

        assertThat(bucket1.orderCount()).isEqualTo(2);
        assertThat(bucket1.revenue()).isEqualByComparingTo("30.00");
        assertThat(bucket2.orderCount()).isEqualTo(1);
        assertThat(bucket2.revenue()).isEqualByComparingTo("5.00");
    }

    @Test
    void defaultsCurrencyToUsd_whenNoFulfilledOrdersInWindow() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "EUR", OrderStatus.PENDING)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, day);
        assertThat(result).allMatch(d -> d.currency().equals("USD"));
    }

    @Test
    void usesFirstFulfilledCurrencySeen_whenFulfilledOrdersExist() {
        LocalDate day = LocalDate.of(2026, 7, 10);
        List<OrderTimeseriesRow> rows = List.of(
                new OrderTimeseriesRow(onDate(day), new BigDecimal("50.00"), "EUR", OrderStatus.FULFILLED)
        );

        List<DailyOrderSummaryDto> result = service.buildDailySummary(rows, day);
        assertThat(result).allMatch(d -> d.currency().equals("EUR"));
    }
}
