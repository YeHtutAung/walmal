package com.walmal.order.application;

import com.walmal.order.application.dto.DailyOrderSummaryDto;
import com.walmal.order.application.dto.OrderAdminSummaryDto;
import com.walmal.order.application.dto.OrderTimeseriesRow;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only service interface for order management operations.
 *
 * <p>ISP: admin concerns (full list, manual status override) are segregated from
 * the customer-facing query and fulfilment interfaces.</p>
 */
public interface OrderAdminService {

    /**
     * Returns a paginated list of all orders across all users,
     * optionally filtered by status.
     *
     * @param status optional status filter; {@code null} returns all orders
     */
    Page<OrderAdminSummaryDto> listAllOrders(OrderStatus status, Pageable pageable);

    /**
     * Searches orders by ID prefix or guest-email substring, case-insensitive.
     *
     * <p>Guard: a {@code null} query or one shorter than 2 characters after trimming
     * returns an empty page without touching the repository. LIKE wildcards
     * ({@code %}, {@code _}, {@code \}) in the query are escaped so they match
     * literally. Matching semantics: the trimmed, lowercased query matches either
     * a prefix of the order's UUID (as rendered lowercase by Postgres) or any
     * substring of the guest email; registered-customer orders (null guest email)
     * only match via the ID predicate.</p>
     *
     * @param q the raw search query; may be {@code null}
     */
    Page<OrderAdminSummaryDto> searchOrders(String q, Pageable pageable);

    /**
     * Applies a manual status transition to an order.
     *
     * <p>Valid transitions: PENDING → CONFIRMED, PENDING → CANCELLED, CONFIRMED → FULFILLED.
     * The domain entity enforces these rules and throws {@link com.walmal.common.exception.BusinessRuleException}
     * for invalid transitions.</p>
     *
     * @param orderId   the order to update
     * @param newStatus the target status
     * @param reason    optional reason for audit log
     */
    void updateStatus(UUID orderId, OrderStatus newStatus, String reason);

    /**
     * Buckets raw order timeseries rows into a 30-day, zero-filled daily summary
     * ending at {@code endDateUtc} (inclusive). Pure aggregation logic — no I/O.
     *
     * <p>All statuses count toward {@code orderCount}; only {@link OrderStatus#FULFILLED}
     * orders contribute to {@code revenue}. The reported currency is taken from the first
     * fulfilled row encountered, defaulting to {@code "USD"} when none exist.</p>
     *
     * @param rows       raw order rows to aggregate (e.g. from a repository projection query)
     * @param endDateUtc the last (most recent) day of the 30-day window, in UTC
     */
    List<DailyOrderSummaryDto> buildDailySummary(List<OrderTimeseriesRow> rows, LocalDate endDateUtc);

    /**
     * Fetches the last 30 days of order rows (UTC, inclusive of today) and buckets
     * them into a zero-filled daily summary via {@link #buildDailySummary}.
     */
    List<DailyOrderSummaryDto> getDailySummary();
}
