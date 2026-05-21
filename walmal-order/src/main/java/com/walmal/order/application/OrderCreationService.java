package com.walmal.order.application;

import com.walmal.order.application.dto.OrderLineItem;
import com.walmal.order.domain.ShippingAddress;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for order creation and cancellation.
 *
 * <p>ISP: creation and cancellation are the write path. Query and fulfilment operations
 * are segregated into separate interfaces to avoid forcing callers to depend on methods
 * they do not use.</p>
 *
 * <p>Architecture rule: implementations depend on {@code ProductCatalogService},
 * {@code ProductPricingService}, {@code InventoryReservationService}, and
 * {@code PaymentGatewayService} interfaces only — never on any Repository from another module.</p>
 */
public interface OrderCreationService {

    /**
     * Creates a new order for the given user.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validates all variants are active via {@code ProductCatalogService}.</li>
     *   <li>Snapshots product name and SKU via {@code ProductCatalogService.findVariantById}.</li>
     *   <li>Fetches prices via {@code ProductPricingService}.</li>
     *   <li>Persists {@code Order} (PENDING) and {@code OrderItem} entities.</li>
     *   <li>Publishes {@code order.created} event.</li>
     *   <li>Reserves stock via {@code InventoryReservationService}.</li>
     *   <li>Charges via {@code PaymentGatewayService}; on failure releases reservation and
     *       cancels the order.</li>
     *   <li>On payment success: confirms reservation, transitions order to CONFIRMED,
     *       publishes {@code order.confirmed}.</li>
     * </ol>
     * The entire operation is wrapped in a single {@code @Transactional} boundary.</p>
     *
     * @param userId          the authenticated user placing the order
     * @param items           one or more line items (variantId, locationId, quantity)
     * @param shippingAddress delivery address snapshot
     * @param currency        ISO-4217 currency code for the order total
     * @return the new order's UUID
     * @throws com.walmal.common.exception.BusinessRuleException if any variant is inactive,
     *         no price is set, or stock is insufficient
     */
    UUID createOrder(UUID userId, List<OrderLineItem> items,
                     ShippingAddress shippingAddress, String currency);

    /**
     * Cancels a PENDING order.
     *
     * <p>Writes to {@code audit_log} BEFORE mutating the order status.
     * Releases inventory reservations and publishes {@code order.cancelled}.</p>
     *
     * @param orderId  the order to cancel
     * @param actorId  the user or system identity performing the cancel (for audit)
     * @throws com.walmal.common.exception.ResourceNotFoundException if the order does not exist
     * @throws com.walmal.common.exception.BusinessRuleException    if the order is not PENDING
     */
    void cancelOrder(UUID orderId, UUID actorId);
}
