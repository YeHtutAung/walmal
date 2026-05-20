package com.walmal.product.domain.event;

import com.walmal.common.event.DomainEvent;

import java.util.UUID;

/**
 * Published when product name, description, brand, or slug is updated.
 * Routing key: {@code product.details.changed}
 * Consumer intent: No MVP consumer — published for future extensibility (e.g., search index refresh).
 */
public class ProductDetailsChangedEvent extends DomainEvent {

    private final UUID productId;
    private final String productName;
    private final String brand;
    private final String status;

    public ProductDetailsChangedEvent(UUID productId, String productName, String brand, String status) {
        super("product.details.changed");
        this.productId = productId;
        this.productName = productName;
        this.brand = brand;
        this.status = status;
    }

    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getBrand() { return brand; }
    public String getStatus() { return status; }
}
