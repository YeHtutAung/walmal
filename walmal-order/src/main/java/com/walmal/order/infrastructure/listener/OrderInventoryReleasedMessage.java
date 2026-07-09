package com.walmal.order.infrastructure.listener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Local POJO representing the payload of an {@code inventory.reservation.released} message
 * as received by the Order module.
 *
 * <p>Architecture rule: this class is a local copy of the relevant fields. The Order module
 * MUST NOT import {@code InventoryReservationReleasedEvent} from walmal-inventory — that
 * would couple the bounded contexts at the class level and violate module boundary rules.</p>
 *
 * @param orderId        the order whose reservation was released
 * @param variantId      the variant that was unreserved
 * @param locationId     the inventory location
 * @param quantity       units released
 * @param conflictReason string representation of the release reason (POS_PRIORITY,
 *                       BUFFER_EXHAUSTED, CANCELLED, EXPIRED)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderInventoryReleasedMessage(
        UUID orderId,
        UUID variantId,
        UUID locationId,
        int quantity,
        String conflictReason
) {}
