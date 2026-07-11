package com.walmal.inventory.domain;

import com.walmal.common.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InventoryStock domain guard methods.
 */
class InventoryStockTest {

    private InventoryLocation location;
    private InventoryStock stock;

    @BeforeEach
    void setUp() {
        location = mock(InventoryLocation.class);
        when(location.getId()).thenReturn(UUID.randomUUID());
        stock = new InventoryStock(UUID.randomUUID(), location, 100, 10);
    }

    @Test
    @DisplayName("should_decrementAvailableAndIncrementReserved_when_reserveSucceeds")
    void should_decrementAvailableAndIncrementReserved_when_reserveSucceeds() {
        stock.reserve(30);
        assertThat(stock.getAvailableQuantity()).isEqualTo(70);
        assertThat(stock.getReservedQuantity()).isEqualTo(30);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_reserveExceedsAvailable")
    void should_throwBusinessRuleException_when_reserveExceedsAvailable() {
        assertThatThrownBy(() -> stock.reserve(101))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock");
    }

    @Test
    @DisplayName("should_decrementReservedOnly_when_confirmSucceeds")
    void should_decrementReservedOnly_when_confirmSucceeds() {
        stock.reserve(30);
        stock.confirm(20);
        assertThat(stock.getAvailableQuantity()).isEqualTo(70);
        assertThat(stock.getReservedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_confirmExceedsReserved")
    void should_throwBusinessRuleException_when_confirmExceedsReserved() {
        stock.reserve(10);
        assertThatThrownBy(() -> stock.confirm(20))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot confirm");
    }

    @Test
    @DisplayName("should_returnStockToAvailable_when_releaseSucceeds")
    void should_returnStockToAvailable_when_releaseSucceeds() {
        stock.reserve(40);
        stock.release(40);
        assertThat(stock.getAvailableQuantity()).isEqualTo(100);
        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_releaseExceedsReserved")
    void should_throwBusinessRuleException_when_releaseExceedsReserved() {
        assertThatThrownBy(() -> stock.release(5))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot release");
    }

    @Test
    @DisplayName("should_returnTrue_when_stockBelowLowStockThreshold")
    void should_returnTrue_when_stockBelowLowStockThreshold() {
        InventoryStock lowStock = new InventoryStock(UUID.randomUUID(), location, 5, 10);
        assertThat(lowStock.isBelowLowStockThreshold()).isTrue();
    }

    @Test
    @DisplayName("should_returnTrue_when_stockIsExhausted")
    void should_returnTrue_when_stockIsExhausted() {
        InventoryStock emptyStock = new InventoryStock(UUID.randomUUID(), location, 0, 10);
        assertThat(emptyStock.isExhausted()).isTrue();
        assertThat(emptyStock.isBelowLowStockThreshold()).isFalse();
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_deltaWouldMakeNegative")
    void should_throwBusinessRuleException_when_deltaWouldMakeNegative() {
        assertThatThrownBy(() -> stock.applyDelta(-101))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("negative stock");
    }

    @Test
    void classifiesAsCritical_whenAvailableAtOrBelowThreshold() {
        assertThat(new InventoryStock(UUID.randomUUID(), location, 10, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.CRITICAL);
        assertThat(new InventoryStock(UUID.randomUUID(), location, 5, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.CRITICAL);
        assertThat(new InventoryStock(UUID.randomUUID(), location, 0, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.CRITICAL);
    }

    @Test
    void classifiesAsLow_whenAvailableBetweenThresholdAndDoubleThresholdInclusive() {
        assertThat(new InventoryStock(UUID.randomUUID(), location, 11, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.LOW);
        assertThat(new InventoryStock(UUID.randomUUID(), location, 20, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.LOW);
    }

    @Test
    void classifiesAsOk_whenAvailableAboveDoubleThreshold() {
        assertThat(new InventoryStock(UUID.randomUUID(), location, 21, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.OK);
        assertThat(new InventoryStock(UUID.randomUUID(), location, 1000, 10).classifyHealth())
                .isEqualTo(StockHealthStatus.OK);
    }
}
