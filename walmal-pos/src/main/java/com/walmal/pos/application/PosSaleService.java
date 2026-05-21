package com.walmal.pos.application;

import com.walmal.pos.application.dto.PosSaleDto;
import com.walmal.pos.application.dto.PosSaleLineItem;
import com.walmal.pos.application.dto.PosSaleSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Public service interface for POS sale recording and queries.
 *
 * <p>ISP: sale creation and query are all consumed by {@code PosSaleController}.
 * No other module injects this interface. The interface is unified for MVP because
 * there is exactly one consumer (see ADR-6 Flag 3).</p>
 *
 * <p>Architecture rule: no implementation may import any Repository bean from another module.</p>
 */
public interface PosSaleService {

    /**
     * Records an online POS sale.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validates all variants are active via {@code ProductCatalogService}.</li>
     *   <li>Fetches product name, SKU, and price snapshots.</li>
     *   <li>Calls {@code OrderCreationService.createOrder()} with a sentinel shipping address.</li>
     *   <li>Persists {@code pos_sale} and {@code pos_sale_items} in a {@code @Transactional} block.</li>
     *   <li>Publishes {@code pos.sale.completed} via {@code DomainEventPublisher}.</li>
     * </ol>
     * MVP gap: if the pos_sale INSERT fails after createOrder() commits, the order is orphaned.
     * Documented in ADR-6 Risk 1. Operator reconciliation query identifies orphaned orders.</p>
     *
     * @param terminalId   the terminal recording the sale
     * @param items        line items (variantId, locationId, quantity)
     * @param cashierId    the authenticated cashier's user UUID
     * @param currency     ISO-4217 currency code
     * @param idempotencyKey optional client-generated key for idempotency (null disables check)
     * @return the persisted sale projection
     * @throws com.walmal.common.exception.ResourceNotFoundException if the terminal does not exist
     * @throws com.walmal.common.exception.BusinessRuleException    if any variant is inactive
     */
    PosSaleDto recordOnlineSale(UUID terminalId, List<PosSaleLineItem> items,
                                 UUID cashierId, String currency, String idempotencyKey);

    /**
     * Returns a sale by primary key including all line items.
     *
     * @param saleId the sale UUID
     * @return full sale projection
     * @throws com.walmal.common.exception.ResourceNotFoundException if the sale does not exist
     */
    PosSaleDto getSale(UUID saleId);

    /**
     * Returns a paginated list of sales for a terminal, ordered by sold_at DESC.
     *
     * @param terminalId the terminal UUID
     * @param pageable   pagination and sort parameters
     * @return page of sale summary projections
     */
    Page<PosSaleSummaryDto> listSalesByTerminal(UUID terminalId, Pageable pageable);
}
