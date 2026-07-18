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
     * Resolves a Stripe webhook's payment intent id to the order it belongs
     * to, for {@code PaymentWebhookServiceImpl}'s reconciliation lookup.
     * {@code payment_reference} is not database-unique (it is only ever set
     * once, at {@code confirm()} time, from a client-supplied PaymentIntent
     * id) — {@code findFirst} makes the "at most one match expected" behavior
     * explicit rather than throwing on an unexpected duplicate.
     */
    Optional<Order> findFirstByPaymentReference(String paymentReference);

    /**
     * Projects raw order rows for the admin daily-summary dashboard. Feeds
     * {@code OrderAdminServiceImpl.buildDailySummary}, which buckets these rows
     * into a zero-filled 30-day window.
     */
    @Query("SELECT new com.walmal.order.application.dto.OrderTimeseriesRow(" +
           "o.createdAt, o.totalAmount, o.currency, o.status) " +
           "FROM Order o WHERE o.createdAt >= :cutoff")
    List<OrderTimeseriesRow> findForDailySummary(@Param("cutoff") Instant cutoff);

    /**
     * Admin search: order-ID prefix match OR guest-email substring match.
     *
     * <p>Both parameters must already be lowercased and LIKE-escaped by the caller
     * ({@code ESCAPE '\'} so wildcards in user input match literally). The
     * {@code lower(CAST(o.id AS string))} fold exists because Postgres renders UUIDs
     * lowercase but an admin may paste an uppercase ID. {@code guestEmail} is null
     * for registered-customer orders — {@code lower(null) LIKE ...} is simply
     * non-matching.</p>
     *
     * <p>Both predicates defeat indexes by construction (per-row cast, leading
     * wildcard) — an acceptable sequential scan at admin order volumes; same MVP
     * tradeoff as product search's ILIKE.</p>
     */
    @Query("SELECT o FROM Order o " +
           "WHERE lower(CAST(o.id AS string)) LIKE :qPrefix ESCAPE '\\' " +
           "OR lower(o.guestEmail) LIKE :qContains ESCAPE '\\'")
    Page<Order> searchByIdPrefixOrGuestEmail(@Param("qPrefix") String qPrefixLowercase,
                                             @Param("qContains") String qContainsLowercase,
                                             Pageable pageable);
}
