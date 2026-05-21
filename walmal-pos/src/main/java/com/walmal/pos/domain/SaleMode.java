package com.walmal.pos.domain;

/**
 * Indicates how a POS sale was initiated.
 *
 * <p>ONLINE — terminal had network access; product and price were fetched from the server
 * in real time and an Order module order was created before persisting the POS sale.
 * OFFLINE — terminal was disconnected; the sale was recorded locally and queued for sync.</p>
 */
public enum SaleMode {
    ONLINE,
    OFFLINE
}
