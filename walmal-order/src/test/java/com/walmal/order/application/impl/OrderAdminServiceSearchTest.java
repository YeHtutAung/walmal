package com.walmal.order.application.impl;

import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.order.application.dto.OrderAdminSummaryDto;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import com.walmal.order.infrastructure.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Follows this codebase's established service-impl unit test convention (see
// OrderAdminServiceDailySummaryTest): MockitoExtension + @Mock fields wired
// manually in @BeforeEach.
@ExtendWith(MockitoExtension.class)
class OrderAdminServiceSearchTest {

    @Mock private OrderRepository orderRepository;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private AuditService auditService;

    private OrderAdminServiceImpl service;

    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new OrderAdminServiceImpl(orderRepository, eventPublisher, auditService);
    }

    @Test
    @DisplayName("should_returnEmptyPageWithoutQueryingRepository_when_qIsSingleCharAfterTrim")
    void should_returnEmptyPageWithoutQueryingRepository_when_qIsSingleCharAfterTrim() {
        Page<OrderAdminSummaryDto> result = service.searchOrders("a", pageable);

        assertThat(result.getContent()).isEmpty();
        verify(orderRepository, never())
                .searchByIdPrefixOrGuestEmail(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("should_returnEmptyPageWithoutQueryingRepository_when_qIsWhitespaceOnly")
    void should_returnEmptyPageWithoutQueryingRepository_when_qIsWhitespaceOnly() {
        Page<OrderAdminSummaryDto> result = service.searchOrders(" ", pageable);

        assertThat(result.getContent()).isEmpty();
        verify(orderRepository, never())
                .searchByIdPrefixOrGuestEmail(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("should_returnEmptyPageWithoutQueryingRepository_when_qIsNull")
    void should_returnEmptyPageWithoutQueryingRepository_when_qIsNull() {
        Page<OrderAdminSummaryDto> result = service.searchOrders(null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(orderRepository, never())
                .searchByIdPrefixOrGuestEmail(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("should_lowercaseAndWrapPatterns_andMapRowsToDto_when_qIsValid")
    void should_lowercaseAndWrapPatterns_andMapRowsToDto_when_qIsValid() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-11T10:00:00Z");

        Order order = mock(Order.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getUserId()).thenReturn(userId);
        when(order.getStatus()).thenReturn(OrderStatus.CONFIRMED);
        when(order.getTotalAmount()).thenReturn(new BigDecimal("1199.99"));
        when(order.getCurrency()).thenReturn("USD");
        when(order.getItemCount()).thenReturn(2);
        when(order.getCreatedAt()).thenReturn(createdAt);

        when(orderRepository.searchByIdPrefixOrGuestEmail(eq("abc12%"), eq("%abc12%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(order), pageable, 1));

        Page<OrderAdminSummaryDto> result = service.searchOrders("AbC12", pageable);

        verify(orderRepository).searchByIdPrefixOrGuestEmail("abc12%", "%abc12%", pageable);
        assertThat(result.getContent()).hasSize(1);
        OrderAdminSummaryDto dto = result.getContent().get(0);
        assertThat(dto.id()).isEqualTo(orderId);
        assertThat(dto.userId()).isEqualTo(userId);
        assertThat(dto.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(dto.totalAmount()).isEqualByComparingTo("1199.99");
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.itemCount()).isEqualTo(2);
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("should_escapeLikeWildcards_when_qContainsUnderscore")
    void should_escapeLikeWildcards_when_qContainsUnderscore() {
        when(orderRepository.searchByIdPrefixOrGuestEmail(eq("a\\_b%"), eq("%a\\_b%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.searchOrders("a_b", pageable);

        verify(orderRepository).searchByIdPrefixOrGuestEmail("a\\_b%", "%a\\_b%", pageable);
    }
}
