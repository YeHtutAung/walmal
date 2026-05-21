package com.walmal.pos.domain;

/**
 * Lifecycle status of a POS terminal.
 *
 * <p>ACTIVE — terminal is operational and may record sales.
 * INACTIVE — terminal has been decommissioned; the API layer rejects sales from it.</p>
 */
public enum TerminalStatus {
    ACTIVE,
    INACTIVE
}
