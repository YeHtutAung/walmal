package com.walmal.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Product image entity — stores MinIO object key and CDN URL for a product or variant image.
 *
 * <p>Table: {@code product_images}.
 * {@code storageKey} holds the MinIO object key. {@code cdnUrl} holds the presigned or CDN URL
 * and may expire — regenerate via {@code FileStorageService.getPresignedUrl()} at read time.</p>
 *
 * <p>A partial unique index on {@code (product_id) WHERE is_primary = TRUE} enforces exactly one
 * primary image per product at the DB layer. The application must clear the current primary before
 * promoting a new one within the same transaction.</p>
 *
 * <p>This entity does NOT extend BaseEntity because the {@code product_images} table has only
 * {@code created_at} (no {@code updated_at}). We declare {@code id} and {@code createdAt} directly.</p>
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "cdn_url", length = 512)
    private String cdnUrl;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductImage() {}

    public ProductImage(Product product, ProductVariant variant, String storageKey,
                        String cdnUrl, String altText, int displayOrder, boolean primary) {
        this.product = product;
        this.variant = variant;
        this.storageKey = storageKey;
        this.cdnUrl = cdnUrl;
        this.altText = altText;
        this.displayOrder = displayOrder;
        this.primary = primary;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public Product getProduct() { return product; }
    public ProductVariant getVariant() { return variant; }
    public String getStorageKey() { return storageKey; }
    public String getCdnUrl() { return cdnUrl; }
    public String getAltText() { return altText; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isPrimary() { return primary; }
    public Instant getCreatedAt() { return createdAt; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setPrimary(boolean primary) { this.primary = primary; }
    public void setAltText(String altText) { this.altText = altText; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public void setCdnUrl(String cdnUrl) { this.cdnUrl = cdnUrl; }
}
