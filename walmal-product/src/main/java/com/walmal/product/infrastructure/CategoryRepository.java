package com.walmal.product.infrastructure;

import com.walmal.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
