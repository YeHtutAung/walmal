package com.walmal.order.application;

import com.walmal.order.application.dto.OrderAdminSummaryDto;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
}
