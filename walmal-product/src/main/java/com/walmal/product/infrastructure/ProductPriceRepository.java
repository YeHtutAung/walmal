package com.walmal.product.infrastructure;

import com.walmal.product.domain.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ProductPrice}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-product} module.</p>
 */
public interface ProductPriceRepository extends JpaRepository<ProductPrice, UUID> {

    /** Returns the price for a variant. There is at most one row per variant (UNIQUE constraint). */
    Optional<ProductPrice> findByVariantId(UUID variantId);
}
