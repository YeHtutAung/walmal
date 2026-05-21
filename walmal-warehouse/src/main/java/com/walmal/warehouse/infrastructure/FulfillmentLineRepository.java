package com.walmal.warehouse.infrastructure;

import com.walmal.warehouse.domain.FulfillmentLine;
import com.walmal.warehouse.domain.FulfillmentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FulfillmentLineRepository extends JpaRepository<FulfillmentLine, UUID> {

    List<FulfillmentLine> findByFulfillmentOrder(FulfillmentOrder fulfillmentOrder);
}
