package com.walmal.product.domain;

/**
 * Lifecycle status for both products and variants.
 *
 * <p>OCP note: adding DRAFT or DISCONTINUED adds a new constant here and a new
 * guard condition in the entity's activate/deactivate methods. Existing callers
 * that compare against ACTIVE continue to compile without modification.</p>
 */
public enum ProductStatus {
    ACTIVE,
    INACTIVE
}
