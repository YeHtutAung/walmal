package com.walmal.order.infrastructure;

import com.walmal.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link OrderItem}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-order} module.</p>
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /**
     * Returns all line items for a given order.
     * Used during order detail reads and cancellation to enumerate reservations.
     */
    List<OrderItem> findByOrderId(UUID orderId);
}
