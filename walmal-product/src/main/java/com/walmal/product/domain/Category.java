package com.walmal.product.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Product category entity — adjacency list hierarchy.
 *
 * <p>Table: {@code product_categories}.
 * Self-referencing FK via {@code parent_id}. Root categories have {@code parent = null}.
 * ON DELETE RESTRICT at the DB layer prevents orphaning children when a category is deleted.</p>
 *
 * <p>{@link BaseEntity} already provides {@code id}, {@code createdAt}, {@code updatedAt},
 * and the {@code @PrePersist} / {@code @PreUpdate} lifecycle callbacks — no need to
 * redeclare them here.</p>
 */
@Entity
@Table(name = "product_categories")
public class Category extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    private List<Category> children = new ArrayList<>();

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 120)
    private String slug;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Category() {}

    public Category(String name, String slug, Category parent) {
        this.name = name;
        this.slug = slug;
        this.parent = parent;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Category getParent() { return parent; }
    public List<Category> getChildren() { return children; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public boolean isActive() { return active; }

    // ── Setters / mutators ────────────────────────────────────────────────────

    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setParent(Category parent) { this.parent = parent; }
    public void setActive(boolean active) { this.active = active; }
}
