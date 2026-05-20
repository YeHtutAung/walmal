package com.walmal.product.application;

import com.walmal.product.application.dto.PriceDto;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module service interface — consumed by Order and POS modules.
 *
 * <p>ISP: Pricing is a distinct concern from catalog lookup. Order uses pricing at checkout;
 * POS uses pricing at till. Neither consumer needs search capabilities from this interface.</p>
 */
public interface ProductPricingService {

    /**
     * Returns the current active price for the given variant.
     * Returns empty if no price record exists for the variant.
     * This is a hot path on every order line and POS scan — results are cached.
     */
    Optional<PriceDto> getCurrentPrice(UUID variantId);

    /**
     * Returns the price for a variant.
     *
     * @throws com.walmal.common.exception.BusinessRuleException if no price is set for this variant.
     *         Prefer this over {@link #getCurrentPrice} when a missing price should halt order creation.
     */
    PriceDto getPriceForVariant(UUID variantId);
}
