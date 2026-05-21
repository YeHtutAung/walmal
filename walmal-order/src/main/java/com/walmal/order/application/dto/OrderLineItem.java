package com.walmal.order.application.dto;

import java.util.UUID;

/**
 * Input record for a single line item when creating an order.
 *
 * @param variantId  the product variant UUID
 * @param locationId the inventory location from which stock will be reserved
 * @param quantity   number of units; must be positive
 */
public record OrderLineItem(UUID variantId, UUID locationId, int quantity) {}
