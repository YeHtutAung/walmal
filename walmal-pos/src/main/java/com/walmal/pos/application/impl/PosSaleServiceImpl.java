package com.walmal.pos.application.impl;

import com.walmal.common.cache.CacheService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.domain.ShippingAddress;
import com.walmal.pos.application.PosSaleService;
import com.walmal.pos.application.dto.*;
import com.walmal.pos.domain.*;
import com.walmal.pos.domain.event.PosSaleCompletedEvent;
import com.walmal.pos.domain.event.PosSaleCompletedEvent.SaleItemSnapshot;
import com.walmal.pos.infrastructure.PosSaleItemRepository;
import com.walmal.pos.infrastructure.PosSaleRepository;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link PosSaleService}.
 *
 * <p>DIP compliance: all cross-module dependencies (Product, Inventory, Order, Cache, Events)
 * are injected as interfaces. No Repository from another module is imported.</p>
 *
 * <p>Transaction note for {@link #recordOnlineSale}:
 * The call to {@code OrderCreationService.createOrder()} happens BEFORE the {@code @Transactional}
 * POS INSERT block. If the POS INSERT fails after {@code createOrder()} commits, the order is
 * orphaned. This is an accepted MVP operational risk documented in ADR-6 Risk 1.
 * The {@code @Transactional} annotation on this method covers only the POS module's own INSERTs.</p>
 */
@Service
public class PosSaleServiceImpl implements PosSaleService {

    private static final Logger log = LoggerFactory.getLogger(PosSaleServiceImpl.class);

    // Cache TTLs
    private static final Duration SALE_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration IDEMPOTENCY_CACHE_TTL = Duration.ofHours(24);

    private final PosSaleRepository posSaleRepository;
    private final PosSaleItemRepository posSaleItemRepository;
    private final PosTerminalRepository posTerminalRepository;
    private final ProductCatalogService productCatalogService;
    private final ProductPricingService productPricingService;
    private final OrderCreationService orderCreationService;
    private final DomainEventPublisher eventPublisher;
    private final CacheService cacheService;

    @Value("${pos.instore-sentinel.country:SG}")
    private String sentinelCountry;

    public PosSaleServiceImpl(
            PosSaleRepository posSaleRepository,
            PosSaleItemRepository posSaleItemRepository,
            PosTerminalRepository posTerminalRepository,
            ProductCatalogService productCatalogService,
            ProductPricingService productPricingService,
            OrderCreationService orderCreationService,
            DomainEventPublisher eventPublisher,
            CacheService cacheService) {
        this.posSaleRepository = posSaleRepository;
        this.posSaleItemRepository = posSaleItemRepository;
        this.posTerminalRepository = posTerminalRepository;
        this.productCatalogService = productCatalogService;
        this.productPricingService = productPricingService;
        this.orderCreationService = orderCreationService;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
    }

    @Override
    @Transactional
    public PosSaleDto recordOnlineSale(UUID terminalId, List<PosSaleLineItem> items,
                                        UUID cashierId, String currency, String idempotencyKey) {

        // Step 1: idempotency cache check — return early if this request was already processed
        if (idempotencyKey != null) {
            String idemCacheKey = "pos:sale:idem:" + idempotencyKey;
            Optional<PosSaleDto> cached = cacheService.get(idemCacheKey, PosSaleDto.class);
            if (cached.isPresent()) {
                log.info("Idempotency cache hit for key={}", idempotencyKey);
                return cached.get();
            }
        }

        // Step 2: load and validate terminal
        PosTerminal terminal = posTerminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("PosTerminal", terminalId));
        terminal.recordActivity();

        // Step 3: validate variants and collect snapshots
        List<LineItemResolved> resolved = new ArrayList<>();
        for (PosSaleLineItem lineItem : items) {
            if (!productCatalogService.isVariantActive(lineItem.variantId())) {
                throw new BusinessRuleException(
                        "Variant " + lineItem.variantId() + " is not active");
            }
            VariantSummaryDto variant = productCatalogService.findVariantById(lineItem.variantId())
                    .orElseThrow(() -> new BusinessRuleException(
                            "Variant not found: " + lineItem.variantId()));
            PriceDto price = productPricingService.getPriceForVariant(lineItem.variantId());
            BigDecimal subtotal = price.amount().multiply(BigDecimal.valueOf(lineItem.quantity()));
            resolved.add(new LineItemResolved(lineItem, variant, price, subtotal));
        }

        BigDecimal totalAmount = resolved.stream()
                .map(LineItemResolved::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Step 4: build sentinel shipping address for in-store orders
        ShippingAddress sentinelAddress = new ShippingAddress(
                "POS IN-STORE", null, terminal.getName(), sentinelCountry, "000000");

        // Step 5: call OrderCreationService — this commits its own transaction.
        // MVP gap: if the POS INSERT below fails, this order is orphaned.
        // Documented in ADR-6 Risk 1. Terminal should retry with the same idempotency key.
        List<OrderLineItem> orderLineItems = resolved.stream()
                .map(r -> new OrderLineItem(
                        r.lineItem().variantId(),
                        r.lineItem().locationId(),
                        r.lineItem().quantity()))
                .toList();
        UUID orderId = orderCreationService.createOrder(cashierId, orderLineItems, sentinelAddress, currency);

        // Step 6: persist POS sale and items (covered by @Transactional on this method)
        PosSale sale = new PosSale(
                terminal, orderId, Instant.now(),
                totalAmount, currency, SaleMode.ONLINE, SyncStatus.N_A, cashierId);
        sale = posSaleRepository.save(sale);

        List<PosSaleItemDto> itemDtos = new ArrayList<>();
        for (LineItemResolved r : resolved) {
            PosSaleItem item = new PosSaleItem(
                    sale,
                    r.lineItem().variantId(),
                    r.variant().productName(),
                    r.variant().sku(),
                    r.lineItem().quantity(),
                    r.price().amount(),
                    currency,
                    r.subtotal(),
                    r.lineItem().locationId());
            posSaleItemRepository.save(item);
            itemDtos.add(toItemDto(item));
        }

        // Save terminal with updated lastSeenAt
        posTerminalRepository.save(terminal);

        PosSaleDto dto = toSaleDto(sale, itemDtos);

        // Step 7: write idempotency cache entry (inside @Transactional — committed together with sale)
        if (idempotencyKey != null) {
            cacheService.put("pos:sale:idem:" + idempotencyKey, dto, IDEMPOTENCY_CACHE_TTL);
        }
        cacheService.put("pos:sale:" + sale.getId(), dto, SALE_CACHE_TTL);

        // Step 8: publish event via DomainEventPublisher (DIP — never RabbitTemplate)
        List<SaleItemSnapshot> eventItems = resolved.stream()
                .map(r -> new SaleItemSnapshot(
                        r.lineItem().variantId(),
                        r.lineItem().quantity(),
                        r.variant().sku()))
                .toList();
        eventPublisher.publish(
                new PosSaleCompletedEvent(
                        terminalId, sale.getId(), orderId, cashierId,
                        eventItems, totalAmount, currency, sale.getSoldAt()),
                "pos.sale.completed");

        log.info("Online POS sale recorded: saleId={} terminalId={} orderId={}",
                sale.getId(), terminalId, orderId);
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public PosSaleDto getSale(UUID saleId) {
        // Check cache first
        Optional<PosSaleDto> cached = cacheService.get("pos:sale:" + saleId, PosSaleDto.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        PosSale sale = posSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("PosSale", saleId));
        List<PosSaleItem> items = posSaleItemRepository.findBySaleId(saleId);
        List<PosSaleItemDto> itemDtos = items.stream().map(this::toItemDto).toList();
        PosSaleDto dto = toSaleDto(sale, itemDtos);

        cacheService.put("pos:sale:" + saleId, dto, SALE_CACHE_TTL);
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PosSaleSummaryDto> listSalesByTerminal(UUID terminalId, Pageable pageable) {
        return posSaleRepository.findByTerminalId(terminalId, pageable)
                .map(this::toSummaryDto);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private PosSaleDto toSaleDto(PosSale sale, List<PosSaleItemDto> items) {
        return new PosSaleDto(
                sale.getId(),
                sale.getTerminal().getId(),
                sale.getOnlineOrderId(),
                sale.getSoldAt(),
                sale.getTotalAmount(),
                sale.getCurrency(),
                sale.getSaleMode(),
                sale.getSyncStatus(),
                items);
    }

    private PosSaleSummaryDto toSummaryDto(PosSale sale) {
        return new PosSaleSummaryDto(
                sale.getId(),
                sale.getSoldAt(),
                sale.getTotalAmount(),
                sale.getSaleMode(),
                sale.getSyncStatus());
    }

    private PosSaleItemDto toItemDto(PosSaleItem item) {
        return new PosSaleItemDto(
                item.getId(),
                item.getVariantId(),
                item.getProductNameSnapshot(),
                item.getSkuSnapshot(),
                item.getQuantity(),
                item.getPriceAtSale(),
                item.getCurrency(),
                item.getSubtotal());
    }

    // ── Private value objects ─────────────────────────────────────────────────

    private record LineItemResolved(
            PosSaleLineItem lineItem,
            VariantSummaryDto variant,
            PriceDto price,
            BigDecimal subtotal
    ) {}
}
