package com.walmal.product.domain.event;

import com.walmal.common.event.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published when the price of a product variant is updated.
 * Routing key: {@code product.price.changed}
 * Consumer intent: Order module re-prices pending order lines; POS refreshes price cache.
 */
public class ProductPriceChangedEvent extends DomainEvent {

    private final UUID variantId;
    private final UUID productId;
    private final BigDecimal oldAmount;
    private final BigDecimal newAmount;
    private final String currency;

    public ProductPriceChangedEvent(UUID variantId, UUID productId,
                                    BigDecimal oldAmount, BigDecimal newAmount,
                                    String currency) {
        super("product.price.changed");
        this.variantId = variantId;
        this.productId = productId;
        this.oldAmount = oldAmount;
        this.newAmount = newAmount;
        this.currency = currency;
    }

    public UUID getVariantId() { return variantId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getOldAmount() { return oldAmount; }
    public BigDecimal getNewAmount() { return newAmount; }
    public String getCurrency() { return currency; }
}
