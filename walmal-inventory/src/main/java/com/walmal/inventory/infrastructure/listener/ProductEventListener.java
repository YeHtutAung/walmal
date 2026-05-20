package com.walmal.inventory.infrastructure.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.inventory.domain.*;
import com.walmal.inventory.infrastructure.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * RabbitMQ listener for product lifecycle events from {@code product.exchange}.
 *
 * <p>Both {@code product.created} and {@code product.deactivated} events are delivered to
 * {@code inventory.product-events.queue}. The routing key is read from the AMQP message header
 * {@code amqp_receivedRoutingKey} to dispatch to the correct handler.</p>
 *
 * <p>Idempotent: {@code handleProductCreated} skips if a stock row already exists.</p>
 */
@Component
public class ProductEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEventListener.class);

    private final InventoryStockRepository stockRepo;
    private final InventoryLocationRepository locationRepo;
    private final InventoryMovementRepository movementRepo;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProductEventListener(InventoryStockRepository stockRepo,
                                  InventoryLocationRepository locationRepo,
                                  InventoryMovementRepository movementRepo,
                                  AuditService auditService,
                                  ObjectMapper objectMapper) {
        this.stockRepo = stockRepo;
        this.locationRepo = locationRepo;
        this.movementRepo = movementRepo;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatches incoming product events based on the AMQP routing key header.
     */
    @RabbitListener(queues = "inventory.product-events.queue")
    @Transactional
    public void handleProductEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody());

        log.debug("Received product event with routing key={}", routingKey);

        try {
            JsonNode node = objectMapper.readTree(body);

            if ("product.created".equals(routingKey)) {
                UUID variantId = UUID.fromString(node.path("variantId").asText());
                String productName = node.path("productName").asText(null);
                handleProductCreated(variantId, productName);

            } else if ("product.deactivated".equals(routingKey)) {
                UUID entityId = UUID.fromString(node.path("entityId").asText());
                String performedBy = node.path("performedBy").asText("system:product-event");
                handleProductDeactivated(entityId, performedBy);

            } else {
                log.debug("Ignored product event with unknown routing key: {}", routingKey);
            }
        } catch (Exception e) {
            log.error("Failed to process product event with routing key={}: {}", routingKey, e.getMessage(), e);
            throw new RuntimeException("Failed to process product event", e);
        }
    }

    /**
     * Creates an inventory_stock row with available_quantity=0 at the default warehouse location.
     * Writes a RECEIPT movement with delta=0 to establish the audit trail baseline.
     * Idempotent: skips if stock row already exists for this variant-location pair.
     */
    private void handleProductCreated(UUID variantId, String productName) {
        log.info("Handling product.created for variant={}", variantId);

        // Default location: first non-buffer active location
        InventoryLocation defaultLocation = locationRepo.findByActiveTrue().stream()
                .filter(l -> !l.isBufferLocation())
                .findFirst()
                .orElse(null);

        if (defaultLocation == null) {
            log.warn("No active non-buffer location found. Cannot initialise stock for variant={}",
                    variantId);
            return;
        }

        // Idempotency check
        boolean exists = stockRepo.findByVariantIdAndLocationId(
                variantId, defaultLocation.getId()).isPresent();
        if (exists) {
            log.debug("Stock row already exists for variant={} at location={}. Skipping.",
                    variantId, defaultLocation.getId());
            return;
        }

        // Create stock row
        InventoryStock stock = new InventoryStock(variantId, defaultLocation, 0, 10);
        stockRepo.save(stock);

        // Baseline movement (delta=0 — establishes audit trail entry)
        movementRepo.save(new InventoryMovement(
                variantId, defaultLocation,
                MovementType.RECEIPT, 0, null, "system:product-event"));

        log.info("Initialised stock for variant={} at location={}",
                variantId, defaultLocation.getId());
    }

    /**
     * Zeros available_quantity on all stock rows for the deactivated variant/entity.
     * Writes audit_log before each mutation (architecture rule).
     */
    private void handleProductDeactivated(UUID entityId, String performedBy) {
        log.info("Handling product.deactivated for entityId={}", entityId);

        List<InventoryStock> stocks = stockRepo.findByVariantId(entityId);

        for (InventoryStock stock : stocks) {
            int currentQty = stock.getAvailableQuantity();

            // Audit BEFORE mutation (architecture rule)
            auditService.log(new AuditEntry(
                    "inventory_stock",
                    stock.getId(),
                    AuditAction.STATUS_CHANGE,
                    "{\"availableQuantity\":" + currentQty + ",\"status\":\"ACTIVE\"}",
                    "{\"availableQuantity\":0,\"status\":\"DEACTIVATED\"}",
                    performedBy));

            stock.setAvailableQuantity(0);
            stockRepo.save(stock);

            // Write movement to track the deactivation
            movementRepo.save(new InventoryMovement(
                    entityId, stock.getLocation(),
                    MovementType.ADJUSTMENT, -currentQty,
                    null, performedBy));
        }

        log.info("Zeroed stock for {} rows for entity={}", stocks.size(), entityId);
    }
}
