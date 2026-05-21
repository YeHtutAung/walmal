package com.walmal.warehouse.infrastructure;

import com.walmal.warehouse.domain.FulfillmentOrder;
import com.walmal.warehouse.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findByFulfillmentOrder(FulfillmentOrder fulfillmentOrder);
}
