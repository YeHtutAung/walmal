package com.walmal.order.domain;

import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.payment.PaymentStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for the Order bounded context.
 *
 * <p>State machine:
 * <pre>
 *   PENDING  →  confirm()    → CONFIRMED
 *   PENDING  →  cancel()     → CANCELLED
 *   CONFIRMED → fulfill()    → FULFILLED
 * </pre>
 * CONFIRMED → CANCELLED is not supported for MVP. Any invalid transition throws
 * {@link BusinessRuleException}.</p>
 *
 * <p>{@code userId} is a cross-module UUID reference to the Auth module. No FK — module boundary rule.</p>
 *
 * <p>{@code version} supports JPA optimistic locking. Never set or modify manually in application code.</p>
 *
 * <p>{@code shippingAddress} is stored as JSONB via {@code @JdbcTypeCode(SqlTypes.JSON)}.
 * Hibernate 6 handles the JSON serialisation natively; no AttributeConverter is needed.
 * The snapshot is fixed at order creation time.</p>
 */
@Entity
@Table(name = "order_orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = true)
    private UUID userId;                     // cross-module UUID ref — no FK; null for guest orders

    @Column(name = "guest_email", length = 320)
    private String guestEmail;               // set only when userId is null (guest checkout)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address", nullable = false, columnDefinition = "jsonb")
    private ShippingAddress shippingAddress;

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Formula("(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = id)")
    private int itemCount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public Order(UUID userId, String currency, BigDecimal totalAmount, ShippingAddress shippingAddress) {
        this(userId, null, currency, totalAmount, shippingAddress);
    }

    public Order(UUID userId, String guestEmail, String currency, BigDecimal totalAmount, ShippingAddress shippingAddress) {
        this.userId = userId;
        this.guestEmail = guestEmail;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING;
        this.paymentStatus = PaymentStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── State machine ─────────────────────────────────────────────────────────

    /**
     * Transitions PENDING → CONFIRMED after successful payment.
     *
     * @param paymentRef gateway-assigned reference string
     * @throws BusinessRuleException if the order is not in PENDING status
     */
    public void confirm(String paymentRef) {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessRuleException(
                    "Order can only be confirmed from PENDING status. Current status: " + this.status);
        }
        this.status = OrderStatus.CONFIRMED;
        this.paymentStatus = PaymentStatus.SUCCESS;
        this.paymentReference = paymentRef;
    }

    /**
     * Transitions PENDING → CANCELLED.
     * CONFIRMED → CANCELLED is not supported in MVP (use the returns/refunds flow post-MVP).
     *
     * @throws BusinessRuleException if the order is not in PENDING status
     */
    public void cancel() {
        if (this.status != OrderStatus.PENDING) {
            throw new BusinessRuleException(
                    "Order cannot be cancelled in status: " + this.status
                    + ". Only PENDING orders may be cancelled.");
        }
        this.status = OrderStatus.CANCELLED;
        this.paymentStatus = PaymentStatus.FAILED;
    }

    /**
     * Transitions CONFIRMED → FULFILLED.
     *
     * @throws BusinessRuleException if the order is not in CONFIRMED status
     */
    public void fulfill() {
        if (this.status != OrderStatus.CONFIRMED) {
            throw new BusinessRuleException(
                    "Order can only be fulfilled from CONFIRMED status. Current status: " + this.status);
        }
        this.status = OrderStatus.FULFILLED;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public int getItemCount() { return itemCount; }
    public UUID getUserId() { return userId; }
    public String getGuestEmail() { return guestEmail; }
    public OrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public String getPaymentReference() { return paymentReference; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }

    public void addItem(OrderItem item) {
        this.items.add(item);
    }
}
