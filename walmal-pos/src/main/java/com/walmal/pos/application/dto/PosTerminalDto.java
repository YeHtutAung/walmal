package com.walmal.pos.application.dto;

import com.walmal.pos.domain.TerminalStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a POS terminal.
 *
 * @param id          terminal UUID
 * @param name        terminal name (usually the store/register name)
 * @param locationId  cross-module ref to the inventory location
 * @param status      current lifecycle status
 * @param lastSeenAt  last time the terminal made an authenticated API call; null if never seen online
 */
public record PosTerminalDto(
        UUID id,
        String name,
        UUID locationId,
        TerminalStatus status,
        Instant lastSeenAt
) {}
