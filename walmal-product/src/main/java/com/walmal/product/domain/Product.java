package com.walmal.product.domain;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.model.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Product aggregate root.
 *
 * <p>Table: {@code product_products}.
 * A product belongs to exactly one {@link Category} and may have many {@link ProductVariant} instances.
 * Status transitions are guarded by {@link #activate()} and {@link #deactivate()} methods — OCP compliant.</p>
 *
 * <p>{@link BaseEntity} provides {@code id}, {@code createdAt}, {@code updatedAt}, and lifecycle callbacks.</p>
 */
@Entity
@Table(name = "product_products")
public class Product extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 300)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "brand", length = 150)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductVariant> variants = new ArrayList<>();

    protected Product() {}

    public Product(String name, String slug, String description, String brand, Category category) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.brand = brand;
        this.category = category;
        this.status = ProductStatus.ACTIVE;
    }

    // ── Status guard methods (OCP — new statuses extend without modifying) ────

    public void activate() {
        if (this.status == ProductStatus.ACTIVE) {
            throw new BusinessRuleException("Product is already ACTIVE");
        }
        this.status = ProductStatus.ACTIVE;
    }

    public void deactivate() {
        if (this.status == ProductStatus.INACTIVE) {
            throw new BusinessRuleException("Product is already INACTIVE");
        }
        this.status = ProductStatus.INACTIVE;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Category getCategory() { return category; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getBrand() { return brand; }
    public ProductStatus getStatus() { return status; }
    public List<ProductVariant> getVariants() { return variants; }

    // ── Setters / mutators ────────────────────────────────────────────────────

    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setDescription(String description) { this.description = description; }
    public void setBrand(String brand) { this.brand = brand; }
    public void setCategory(Category category) { this.category = category; }
}
