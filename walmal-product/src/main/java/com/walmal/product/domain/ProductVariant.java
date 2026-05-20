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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Product variant entity — a specific sellable SKU under a {@link Product}.
 *
 * <p>Table: {@code product_variants}.
 * SKU is globally unique and is the cross-module reference identifier stored by
 * Inventory and Order modules (as a UUID column, no FK declared on their side).</p>
 *
 * <p>{@code attributes} is stored as JSONB and holds arbitrary dimension data
 * (e.g. {@code {"size": "L", "colour": "Blue"}}) without requiring schema changes per product type.</p>
 *
 * <p>{@link BaseEntity} provides {@code id}, {@code createdAt}, {@code updatedAt}, and lifecycle callbacks.</p>
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sku", nullable = false, unique = true, length = 100)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "barcode", length = 50)
    private String barcode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProductStatus status = ProductStatus.ACTIVE;

    @OneToOne(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    private ProductPrice price;

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductImage> images = new ArrayList<>();

    protected ProductVariant() {}

    public ProductVariant(Product product, String sku, String name, String barcode,
                          Map<String, Object> attributes) {
        this.product = product;
        this.sku = sku;
        this.name = name;
        this.barcode = barcode;
        this.attributes = attributes;
        this.status = ProductStatus.ACTIVE;
    }

    // ── Status guard methods ──────────────────────────────────────────────────

    public void activate() {
        if (this.status == ProductStatus.ACTIVE) {
            throw new BusinessRuleException("Variant is already ACTIVE");
        }
        this.status = ProductStatus.ACTIVE;
    }

    public void deactivate() {
        if (this.status == ProductStatus.INACTIVE) {
            throw new BusinessRuleException("Variant is already INACTIVE");
        }
        this.status = ProductStatus.INACTIVE;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public Product getProduct() { return product; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getBarcode() { return barcode; }
    public Map<String, Object> getAttributes() { return attributes; }
    public ProductStatus getStatus() { return status; }
    public ProductPrice getPrice() { return price; }
    public List<ProductImage> getImages() { return images; }

    // ── Setters / mutators ────────────────────────────────────────────────────

    public void setSku(String sku) { this.sku = sku; }
    public void setName(String name) { this.name = name; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    public void setPrice(ProductPrice price) { this.price = price; }
}
