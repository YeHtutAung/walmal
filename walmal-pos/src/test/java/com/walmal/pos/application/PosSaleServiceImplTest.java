package com.walmal.pos.application;

import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderCreationService;
import com.walmal.pos.application.dto.PosSaleDto;
import com.walmal.pos.application.dto.PosSaleLineItem;
import com.walmal.pos.application.impl.PosSaleServiceImpl;
import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.PosSale;
import com.walmal.pos.domain.SaleMode;
import com.walmal.pos.domain.SyncStatus;
import com.walmal.pos.infrastructure.PosSaleItemRepository;
import com.walmal.pos.infrastructure.PosSaleRepository;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import com.walmal.product.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosSaleServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PosSaleServiceImplTest {

    @Mock PosSaleRepository posSaleRepository;
    @Mock PosSaleItemRepository posSaleItemRepository;
    @Mock PosTerminalRepository posTerminalRepository;
    @Mock ProductCatalogService productCatalogService;
    @Mock ProductPricingService productPricingService;
    @Mock OrderCreationService orderCreationService;
    @Mock DomainEventPublisher eventPublisher;
    @Mock CacheService cacheService;

    @InjectMocks PosSaleServiceImpl service;

    private UUID terminalId;
    private UUID variantId;
    private UUID locationId;
    private UUID cashierId;
    private PosTerminal terminal;

    @BeforeEach
    void setUp() {
        terminalId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        cashierId = UUID.randomUUID();
        terminal = new PosTerminal("Store A Terminal 1", locationId);
    }

    private void setupHappyPath() {
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));
        when(posTerminalRepository.save(any())).thenReturn(terminal);
        when(productCatalogService.isVariantActive(variantId)).thenReturn(true);
        when(productCatalogService.findVariantById(variantId)).thenReturn(Optional.of(
                new VariantSummaryDto(variantId, UUID.randomUUID(), "SKU-001", "BC-001",
                        "Test Product", "Red", "M", ProductStatus.ACTIVE)));
        when(productPricingService.getPriceForVariant(variantId)).thenReturn(
                new PriceDto(variantId, BigDecimal.valueOf(49.99), "SGD", Instant.now()));
        when(orderCreationService.createOrder(any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());
        when(posSaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(posSaleItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cacheService.get(anyString(), eq(PosSaleDto.class))).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("should_recordOnlineSale_successfully_and_publishEvent")
    void should_recordOnlineSale_successfully_and_publishEvent() {
        setupHappyPath();

        List<PosSaleLineItem> items = List.of(new PosSaleLineItem(variantId, locationId, 2));

        PosSaleDto dto = service.recordOnlineSale(terminalId, items, cashierId, "SGD", null);

        assertThat(dto).isNotNull();
        assertThat(dto.saleMode()).isEqualTo(SaleMode.ONLINE);
        assertThat(dto.syncStatus()).isEqualTo(SyncStatus.N_A);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(eventCaptor.capture(), eq("pos.sale.completed"));
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("pos.sale.completed");
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_variantInactive_during_onlineSale")
    void should_throwBusinessRuleException_when_variantInactive_during_onlineSale() {
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));
        when(cacheService.get(anyString(), eq(PosSaleDto.class))).thenReturn(Optional.empty());
        when(productCatalogService.isVariantActive(variantId)).thenReturn(false);

        List<PosSaleLineItem> items = List.of(new PosSaleLineItem(variantId, locationId, 1));

        assertThatThrownBy(() -> service.recordOnlineSale(terminalId, items, cashierId, "SGD", null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");

        verify(orderCreationService, never()).createOrder(any(), any(), any(), any(), any());
        verify(posSaleRepository, never()).save(any(PosSale.class));
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_terminalNotFound")
    void should_throwResourceNotFoundException_when_terminalNotFound() {
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.empty());
        when(cacheService.get(anyString(), eq(PosSaleDto.class))).thenReturn(Optional.empty());

        List<PosSaleLineItem> items = List.of(new PosSaleLineItem(variantId, locationId, 1));

        assertThatThrownBy(() -> service.recordOnlineSale(terminalId, items, cashierId, "SGD", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("should_returnCachedDto_when_idempotencyKeyHit")
    void should_returnCachedDto_when_idempotencyKeyHit() {
        String idempotencyKey = "idem-key-123";
        PosSaleDto cachedDto = new PosSaleDto(
                UUID.randomUUID(), terminalId, UUID.randomUUID(),
                Instant.now(), BigDecimal.valueOf(49.99), "SGD",
                SaleMode.ONLINE, SyncStatus.N_A, List.of());

        when(cacheService.get("pos:sale:idem:" + idempotencyKey, PosSaleDto.class))
                .thenReturn(Optional.of(cachedDto));

        List<PosSaleLineItem> items = List.of(new PosSaleLineItem(variantId, locationId, 1));

        PosSaleDto result = service.recordOnlineSale(terminalId, items, cashierId, "SGD", idempotencyKey);

        assertThat(result).isEqualTo(cachedDto);
        // createOrder should NOT be called on cache hit
        verify(orderCreationService, never()).createOrder(any(), any(), any(), any(), any());
        verify(posTerminalRepository, never()).findById(any());
    }
}
