package com.walmal.product.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Active price for a product variant — one row per variant (UNIQUE constraint on {@code variant_id}).
 *
 * <p>Table: {@code product_prices}.
 * A "price change" is an UPDATE on this row, not an INSERT. {@code AuditService.log()} with
 * {@code AuditAction.UPDATE} captures the old amount before the update executes.</p>
 *
 * <p>The table has no created_at/updated_at by design — the audit_log captures full price
 * change history via old_value/new_value JSONB. {@link BaseEntity} provides {@code id}
 * plus the lifecycle callbacks, but the DB table does not have those timestamp columns.
 * We override the mapping here to avoid JPA trying to persist those columns.</p>
 *
 * <p>This entity does NOT extend BaseEntity because the {@code product_prices} table
 * intentionally omits {@code created_at} and {@code updated_at} columns (per the ADR and
 * migration). Extending BaseEntity would cause JPA to attempt writing those columns.</p>
 */
@Entity
@Table(name = "product_prices")
public class ProductPrice {

    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private java.util.UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false, unique = true)
    private ProductVariant variant;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    protected ProductPrice() {}

    public ProductPrice(ProductVariant variant, BigDecimal amount, String currency, Instant effectiveFrom) {
        this.variant = variant;
        this.amount = amount;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public java.util.UUID getId() { return id; }
    public ProductVariant getVariant() { return variant; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEffectiveFrom() { return effectiveFrom; }

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
}
