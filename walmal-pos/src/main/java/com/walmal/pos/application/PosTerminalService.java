package com.walmal.pos.application;

import com.walmal.pos.application.dto.PosTerminalDto;

import java.util.UUID;

/**
 * Public service interface for POS terminal lifecycle management.
 *
 * <p>ISP: terminal management (register, deactivate, query) is consumed only by
 * {@code PosTerminalController}. No other module injects this interface.</p>
 *
 * <p>Architecture rule: no implementation of this interface may inject any Repository
 * bean from another module.</p>
 */
public interface PosTerminalService {

    /**
     * Registers a new POS terminal at the given inventory location.
     *
     * <p>Creates a {@code pos_terminals} row with status=ACTIVE.
     * No audit log required — INSERT is not a destructive operation.</p>
     *
     * @param name       display name of the terminal (e.g. "Main Store Register 1")
     * @param locationId inventory location UUID (cross-module ref, validated at app layer)
     * @return the new terminal UUID
     */
    UUID registerTerminal(String name, UUID locationId);

    /**
     * Deactivates an ACTIVE terminal.
     *
     * <p>Writes to {@code audit_log} BEFORE mutating {@code pos_terminals.status}.
     * If the terminal is already INACTIVE, throws {@link com.walmal.common.exception.BusinessRuleException}.</p>
     *
     * @param terminalId the terminal to deactivate
     * @throws com.walmal.common.exception.ResourceNotFoundException if the terminal does not exist
     * @throws com.walmal.common.exception.BusinessRuleException    if already INACTIVE
     */
    void deactivateTerminal(UUID terminalId);

    /**
     * Returns a terminal by primary key.
     *
     * @param terminalId the terminal UUID
     * @return terminal projection
     * @throws com.walmal.common.exception.ResourceNotFoundException if the terminal does not exist
     */
    PosTerminalDto getTerminal(UUID terminalId);
}
