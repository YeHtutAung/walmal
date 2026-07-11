package com.walmal.product.infrastructure;

import com.walmal.product.application.dto.CategoryProductVariantRow;
import com.walmal.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Category}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-product} module.</p>
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    /** Returns all root categories (no parent). */
    List<Category> findByParentIsNull();

    /** Returns direct children of the given parent category. */
    List<Category> findByParentId(UUID parentId);

    /**
     * Flat category → product → variant rows for every category, including categories with
     * zero products and products with zero variants (LEFT JOIN preserves both).
     */
    @Query("SELECT new com.walmal.product.application.dto.CategoryProductVariantRow(" +
           "c.id, c.name, p.id, v.id) " +
           "FROM Category c " +
           "LEFT JOIN Product p ON p.category = c " +
           "LEFT JOIN p.variants v")
    List<CategoryProductVariantRow> findCategoryProductVariantRows();
}
