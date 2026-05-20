package com.walmal.inventory.infrastructure.messaging;

import java.util.UUID;

/**
 * Inbound message model for the {@code product.created} event from product.exchange.
 * Field names match the JSON serialisation of {@code ProductCreatedEvent} from walmal-product.
 * No import of walmal-product event classes is permitted — only {@code ProductCatalogService}
 * (the interface) may be imported from walmal-product.
 */
public record ProductCreatedMessage(
        String eventType,
        UUID variantId,
        UUID productId,
        String sku,
        String productName
) {}
