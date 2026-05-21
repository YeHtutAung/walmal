package com.walmal.order.infrastructure;

import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Order}.
 *
 * <p>Architecture rule: this repository MUST NOT be injected into any class
 * outside the {@code walmal-order} module.</p>
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Retrieves all orders for a given user, paginated.
     * Primary access pattern for the customer order history endpoint.
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    /**
     * Retrieves a specific order only if it has the expected status.
     * Used by conflict resolution to guard against double-cancellation.
     */
    Optional<Order> findByIdAndStatus(UUID id, OrderStatus status);
}
