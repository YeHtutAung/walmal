package com.walmal.product.application.dto;

import com.walmal.product.domain.ProductStatus;

import java.util.UUID;

/**
 * Minimal variant projection used by cross-module consumers (Order, POS).
 * Exposes only what is needed for order creation and POS barcode scans.
 */
public record VariantSummaryDto(
        UUID variantId,
        UUID productId,
        String sku,
        String barcode,
        String productName,
        String color,
        String size,
        ProductStatus status
) {}
