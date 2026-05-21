package com.walmal.pos.api.dto;

import com.walmal.pos.application.dto.PosSaleLineItem;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/pos/sales}.
 *
 * @param terminalId the terminal recording the sale
 * @param items      line items (variantId, locationId, quantity)
 * @param cashierId  the authenticated cashier's user UUID (optional; extracted from auth context)
 * @param currency   ISO-4217 currency code
 */
public record RecordOnlineSaleRequest(
        @NotNull
        UUID terminalId,

        @NotEmpty
        List<PosSaleLineItem> items,

        UUID cashierId,   // optional — controller extracts from auth principal if null

        @NotNull @Size(min = 3, max = 3)
        String currency
) {}
