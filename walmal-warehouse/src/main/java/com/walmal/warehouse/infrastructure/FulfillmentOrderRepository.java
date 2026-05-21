package com.walmal.warehouse.infrastructure;

import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.domain.FulfillmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FulfillmentOrderRepository extends JpaRepository<FulfillmentOrder, UUID> {

    Optional<FulfillmentOrder> findByOrderId(UUID orderId);

    List<FulfillmentOrder> findByStatus(FulfillmentStatus status);
}
