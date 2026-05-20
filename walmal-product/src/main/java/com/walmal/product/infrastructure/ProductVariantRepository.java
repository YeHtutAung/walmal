package com.walmal.product.infrastructure;

import com.walmal.product.domain.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ProductVariant}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-product} module.</p>
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    Optional<ProductVariant> findBySku(String sku);

    List<ProductVariant> findByProductId(UUID productId);

    /**
     * Fetches variant with its parent product in one query to support
     * the {@code isVariantActive} check without a second DB round-trip.
     */
    @Query("SELECT v FROM ProductVariant v JOIN FETCH v.product p WHERE v.id = :id")
    Optional<ProductVariant> findByIdWithProduct(@Param("id") UUID id);
}
