package com.walmal.order.application;

import com.walmal.order.application.dto.OrderDetailDto;
import com.walmal.order.application.dto.OrderSummaryDto;
import com.walmal.order.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Public service interface for order reads.
 *
 * <p>ISP: queries are a distinct concern from writes. Controllers or other consumers
 * that only display order data inject this interface without pulling in write-path dependencies.</p>
 */
public interface OrderQueryService {

    /**
     * Returns the full detail of a single order including all line items.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if the order does not exist
     */
    OrderDetailDto getOrder(UUID orderId);

    /**
     * Returns a paginated list of summary projections for all orders placed by a user.
     */
    Page<OrderSummaryDto> listOrdersByUser(UUID userId, Pageable pageable);

    /**
     * Returns the current status of an order.
     *
     * @throws com.walmal.common.exception.ResourceNotFoundException if the order does not exist
     */
    OrderStatus getOrderStatus(UUID orderId);
}
