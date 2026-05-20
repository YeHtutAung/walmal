package com.walmal.inventory.infrastructure.messaging;

import java.util.UUID;

/**
 * Inbound message model for the {@code product.deactivated} event from product.exchange.
 * Field names match the JSON serialisation of {@code ProductDeactivatedEvent} from walmal-product.
 *
 * <p>{@code entityType} is either "PRODUCT" or "VARIANT". The listener acts on VARIANT events
 * only — PRODUCT-level deactivation requires zeroing all variants, resolved at application time.</p>
 */
public record ProductDeactivatedMessage(
        String eventType,
        UUID entityId,       // variant id (when entityType=VARIANT) or product id (when PRODUCT)
        String entityType,   // "PRODUCT" or "VARIANT"
        String sku,          // present when entityType=VARIANT, null otherwise
        UUID productId,
        String performedBy
) {}
