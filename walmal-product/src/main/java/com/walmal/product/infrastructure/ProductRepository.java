package com.walmal.product.infrastructure;

import com.walmal.product.domain.Product;
import com.walmal.product.domain.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Product}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-product} module.</p>
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySlug(String slug);

    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryIdAndStatus(UUID categoryId, ProductStatus status, Pageable pageable);

    /**
     * Widened product search across name, brand, variant SKU, and variant barcode.
     *
     * <p>The parameter must be a pre-built lowercase LIKE pattern with
     * {@code %}, {@code _}, and {@code \} in the user input escaped by
     * {@code \} (e.g. {@code %query%}) — every predicate declares
     * {@code ESCAPE '\'} so wildcards in user input match literally.
     * The explicit {@code countQuery} is mandatory: Spring Data's derived
     * count for a {@code SELECT DISTINCT} + join can over-count joined rows,
     * corrupting {@code totalElements}. The optional {@code status} predicate
     * MUST appear in both query strings for the same reason — a mismatch
     * silently corrupts {@code totalElements}.</p>
     *
     * <p>{@code status} is nullable: null = all statuses (the admin list
     * depends on this); non-null filters to that status (storefront opt-in).</p>
     */
    @Query(value = "SELECT DISTINCT p FROM Product p LEFT JOIN p.variants v " +
                   "WHERE (lower(p.name) LIKE :q ESCAPE '\\' OR lower(p.brand) LIKE :q ESCAPE '\\' " +
                   "OR lower(v.sku) LIKE :q ESCAPE '\\' OR lower(v.barcode) LIKE :q ESCAPE '\\') " +
                   "AND (:status IS NULL OR p.status = :status)",
           countQuery = "SELECT COUNT(DISTINCT p) FROM Product p LEFT JOIN p.variants v " +
                   "WHERE (lower(p.name) LIKE :q ESCAPE '\\' OR lower(p.brand) LIKE :q ESCAPE '\\' " +
                   "OR lower(v.sku) LIKE :q ESCAPE '\\' OR lower(v.barcode) LIKE :q ESCAPE '\\') " +
                   "AND (:status IS NULL OR p.status = :status)")
    Page<Product> searchByNameBrandSkuOrBarcode(@Param("q") String qContainsLowercase,
                                                @Param("status") ProductStatus status,
                                                Pageable pageable);
}
