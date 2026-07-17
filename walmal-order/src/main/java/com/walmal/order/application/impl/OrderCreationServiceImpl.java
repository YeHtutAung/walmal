package com.walmal.order.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.common.payment.PaymentGatewayService;
import com.walmal.common.payment.PaymentResult;
import com.walmal.common.payment.PaymentStatus;
import com.walmal.inventory.application.InventoryReservationService;
import com.walmal.inventory.application.InventoryReservationService.ReservationLineItem;
import com.walmal.inventory.domain.ConflictReason;
import com.walmal.order.application.OrderCreationService;
import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.domain.*;
import com.walmal.order.domain.event.OrderCancelledEvent;
import com.walmal.order.domain.event.OrderConfirmedEvent;
import com.walmal.order.domain.event.OrderCreatedEvent;
import com.walmal.order.domain.event.OrderCreatedEvent.OrderItemSnapshot;
import com.walmal.order.infrastructure.OrderRepository;
import com.walmal.product.application.ProductCatalogService;
import com.walmal.product.application.ProductPricingService;
import com.walmal.product.application.dto.PriceDto;
import com.walmal.product.application.dto.VariantSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link OrderCreationService}.
 *
 * <p>DIP compliance: all cross-module dependencies (Inventory, Product, Payment) are
 * injected as interfaces. No Repository from another module is imported.</p>
 *
 * <p>Transaction boundary: the entire {@code createOrder} method is one transaction.
 * Known MVP gap (documented in ADR-5): if {@code confirmReservation()} throws after
 * payment succeeds, the transaction rolls back but the payment has already been charged.
 * A compensating refund mechanism is out of scope for MVP.</p>
 */
@Service
public class OrderCreationServiceImpl implements OrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationServiceImpl.class);

    private final OrderRepository orderRepository;
    private final ProductCatalogService productCatalogService;
    private final ProductPricingService productPricingService;
    private final InventoryReservationService inventoryReservationService;
    private final PaymentGatewayService paymentGatewayService;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public OrderCreationServiceImpl(
            OrderRepository orderRepository,
            ProductCatalogService productCatalogService,
            ProductPricingService productPricingService,
            InventoryReservationService inventoryReservationService,
            PaymentGatewayService paymentGatewayService,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        this.orderRepository = orderRepository;
        this.productCatalogService = productCatalogService;
        this.productPricingService = productPricingService;
        this.inventoryReservationService = inventoryReservationService;
        this.paymentGatewayService = paymentGatewayService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public UUID createGuestOrder(String guestEmail, List<OrderLineItem> items,
                                  ShippingAddress shippingAddress, String currency) {
        return doCreateOrder(null, guestEmail, items, shippingAddress, currency);
    }

    @Override
    @Transactional
    public UUID createOrder(UUID userId, List<OrderLineItem> items,
                            ShippingAddress shippingAddress, String currency) {
        return doCreateOrder(userId, null, items, shippingAddress, currency);
    }

    private UUID doCreateOrder(UUID userId, String guestEmail, List<OrderLineItem> items,
                                ShippingAddress shippingAddress, String currency) {

        // Step 1 & 2: validate variants and collect snapshots
        List<LineItemResolved> resolved = new ArrayList<>();
        for (OrderLineItem lineItem : items) {
            if (!productCatalogService.isVariantActive(lineItem.variantId())) {
                throw new BusinessRuleException(
                        "Variant " + lineItem.variantId() + " is not active");
            }
            VariantSummaryDto variant = productCatalogService.findVariantById(lineItem.variantId())
                    .orElseThrow(() -> new BusinessRuleException(
                            "Variant not found: " + lineItem.variantId()));

            // Step 3: fetch price
            PriceDto price = productPricingService.getPriceForVariant(lineItem.variantId());

            // The order currency is server-authoritative: it must match the
            // variant's own price currency. Without this a client could send
            // currency="MYR" for USD-priced goods and (with a real gateway) be
            // charged 999 MYR instead of 999 USD — the backend must never trust
            // a client-supplied currency. (Security review finding #6.)
            if (currency == null || !currency.equalsIgnoreCase(price.currency())) {
                throw new BusinessRuleException(
                        "Order currency " + currency + " does not match the price currency "
                        + price.currency() + " for variant " + lineItem.variantId());
            }

            // Step 4: compute subtotal
            BigDecimal subtotal = price.amount().multiply(BigDecimal.valueOf(lineItem.quantity()));

            resolved.add(new LineItemResolved(lineItem, variant, price, subtotal));
        }

        // Compute total
        BigDecimal totalAmount = resolved.stream()
                .map(LineItemResolved::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Step 5: persist Order (PENDING)
        Order order = new Order(userId, guestEmail, currency, totalAmount, shippingAddress);
        order = orderRepository.save(order);
        UUID orderId = order.getId();

        // Attach items (cascade will persist them)
        for (LineItemResolved r : resolved) {
            OrderItem item = new OrderItem(
                    order,
                    r.lineItem().variantId(),
                    r.variant().productName(),
                    r.variant().sku(),
                    r.lineItem().quantity(),
                    r.price().amount(),
                    currency,
                    r.subtotal(),
                    r.lineItem().locationId());
            order.addItem(item);
        }
        order = orderRepository.save(order);

        // Build item snapshots for events
        List<OrderItemSnapshot> snapshots = resolved.stream()
                .map(r -> new OrderItemSnapshot(
                        r.lineItem().variantId(),
                        r.lineItem().locationId(),
                        r.lineItem().quantity(),
                        r.variant().sku()))
                .toList();

        // Publish order.created immediately after persistence
        eventPublisher.publish(
                new OrderCreatedEvent(orderId, userId, snapshots, totalAmount, currency, order.getCreatedAt()),
                "order.created");

        // Step 6: reserve stock — if this throws, the transaction rolls back
        List<ReservationLineItem> reservationItems = resolved.stream()
                .map(r -> new ReservationLineItem(
                        r.lineItem().variantId(),
                        r.lineItem().locationId(),
                        r.lineItem().quantity()))
                .toList();
        inventoryReservationService.reserveStock(orderId, reservationItems);

        // Step 7: charge payment
        PaymentResult paymentResult = paymentGatewayService.charge(orderId, totalAmount, currency);

        if (paymentResult.status() != PaymentStatus.SUCCESS) {
            log.warn("Payment failed for order={}. Releasing reservation.", orderId);

            // Release inventory reservation
            inventoryReservationService.releaseReservation(orderId, ConflictReason.CANCELLED);

            // Audit BEFORE cancel mutation
            auditService.log(new AuditEntry(
                    "order_orders", orderId, AuditAction.STATUS_CHANGE,
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"CANCELLED\",\"reason\":\"PAYMENT_FAILED\"}",
                    "system:payment"));

            order.cancel();
            orderRepository.save(order);

            eventPublisher.publish(
                    new OrderCancelledEvent(orderId, userId, "PAYMENT_FAILED", Instant.now()),
                    "order.cancelled");

            return orderId;
        }

        // Step 8: payment succeeded — confirm reservation and order
        // MVP gap: if confirmReservation() throws here after payment, the transaction rolls back
        // but the payment has already been charged externally. Documented in ADR-5.
        inventoryReservationService.confirmReservation(orderId);

        order.confirm(paymentResult.paymentReference());
        order = orderRepository.save(order);

        eventPublisher.publish(
                new OrderConfirmedEvent(orderId, userId, snapshots, shippingAddress, Instant.now()),
                "order.confirmed");

        log.info("Order created and confirmed: orderId={}", orderId);
        return orderId;
    }

    @Override
    @Transactional
    public void cancelOrder(UUID orderId, UUID actorId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessRuleException(
                    "Order cannot be cancelled in status: " + order.getStatus());
        }

        // Audit BEFORE mutation (architecture rule)
        auditService.log(new AuditEntry(
                "order_orders", orderId, AuditAction.STATUS_CHANGE,
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"CANCELLED\",\"reason\":\"CUSTOMER_REQUEST\"}",
                actorId.toString()));

        // Release reservation
        inventoryReservationService.releaseReservation(orderId, ConflictReason.CANCELLED);

        order.cancel();
        orderRepository.save(order);

        eventPublisher.publish(
                new OrderCancelledEvent(orderId, order.getUserId(), "CUSTOMER_REQUEST", Instant.now()),
                "order.cancelled");

        log.info("Order cancelled: orderId={} by actorId={}", orderId, actorId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Internal value object holding a resolved line item with all computed fields.
     */
    private record LineItemResolved(
            OrderLineItem lineItem,
            VariantSummaryDto variant,
            PriceDto price,
            BigDecimal subtotal
    ) {}
}
