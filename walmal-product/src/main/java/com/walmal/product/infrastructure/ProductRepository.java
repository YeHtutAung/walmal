package com.walmal.product.infrastructure;

import com.walmal.product.domain.Product;
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

    /**
     * Widened product search across name, brand, variant SKU, and variant barcode.
     *
     * <p>The parameter must be a pre-built lowercase LIKE pattern with
     * {@code %}, {@code _}, and {@code \} in the user input escaped by
     * {@code \} (e.g. {@code %query%}) — every predicate declares
     * {@code ESCAPE '\'} so wildcards in user input match literally.
     * The explicit {@code countQuery} is mandatory: Spring Data's derived
     * count for a {@code SELECT DISTINCT} + join can over-count joined rows,
     * corrupting {@code totalElements}.</p>
     */
    @Query(value = "SELECT DISTINCT p FROM Product p LEFT JOIN p.variants v " +
                   "WHERE lower(p.name) LIKE :q ESCAPE '\\' OR lower(p.brand) LIKE :q ESCAPE '\\' " +
                   "OR lower(v.sku) LIKE :q ESCAPE '\\' OR lower(v.barcode) LIKE :q ESCAPE '\\'",
           countQuery = "SELECT COUNT(DISTINCT p) FROM Product p LEFT JOIN p.variants v " +
                   "WHERE lower(p.name) LIKE :q ESCAPE '\\' OR lower(p.brand) LIKE :q ESCAPE '\\' " +
                   "OR lower(v.sku) LIKE :q ESCAPE '\\' OR lower(v.barcode) LIKE :q ESCAPE '\\'")
    Page<Product> searchByNameBrandSkuOrBarcode(@Param("q") String qContainsLowercase, Pageable pageable);
}
