package com.walmal.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistent home-page document, one row per lifecycle status
 * ({@link ContentStatus#DRAFT} / {@link ContentStatus#PUBLISHED}).
 *
 * <p>The whole editorial {@link HomeContent} document is stored as a single JSONB
 * value via {@code @JdbcTypeCode(SqlTypes.JSON)} — same mapping as
 * {@code Order.shippingAddress}. Hibernate 6 handles JSON serialisation natively;
 * no {@code AttributeConverter} is required.</p>
 */
@Entity
@Table(name = "content_home")
public class ContentHome {

    @Id
    @Column(name = "status", length = 16, nullable = false)
    private String status;                    // ContentStatus.DRAFT | PUBLISHED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb", nullable = false)
    private HomeContent content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    protected ContentHome() {}

    public ContentHome(String status, HomeContent content, String updatedBy) {
        this.status = status;
        this.content = content;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public void update(HomeContent content, String updatedBy) {
        this.content = content;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getStatus() { return status; }
    public HomeContent getContent() { return content; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
