package com.walmal.order.infrastructure;

import com.walmal.order.application.dto.OrderTimeseriesRow;
import com.walmal.order.domain.Order;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * Retrieves all orders with the given status, paginated.
     * Backs the admin list's status filter.
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Guest email lookup for the notification module. The {@code userId IS NULL}
     * predicate guarantees registered-user orders return empty by construction.
     */
    @Query("SELECT o.guestEmail FROM Order o WHERE o.id = :orderId AND o.userId IS NULL AND o.guestEmail IS NOT NULL")
    Optional<String> findGuestEmailByOrderId(@Param("orderId") UUID orderId);

    /**
     * Projects raw order rows for the admin daily-summary dashboard. Feeds
     * {@code OrderAdminServiceImpl.buildDailySummary}, which buckets these rows
     * into a zero-filled 30-day window.
     */
    @Query("SELECT new com.walmal.order.application.dto.OrderTimeseriesRow(" +
           "o.createdAt, o.totalAmount, o.currency, o.status) " +
           "FROM Order o WHERE o.createdAt >= :cutoff")
    List<OrderTimeseriesRow> findForDailySummary(@Param("cutoff") Instant cutoff);
}
