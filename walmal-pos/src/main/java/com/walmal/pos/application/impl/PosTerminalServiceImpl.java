package com.walmal.pos.application.impl;

import com.walmal.common.audit.AuditAction;
import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.pos.application.PosTerminalService;
import com.walmal.pos.application.dto.PosTerminalDto;
import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementation of {@link PosTerminalService}.
 *
 * <p>DIP compliance: cross-module dependencies are via interfaces only.
 * No Repository from another module is imported.</p>
 *
 * <p>Audit compliance: {@code deactivateTerminal()} writes to audit_log BEFORE
 * the status mutation (CLAUDE.md architecture rule).</p>
 */
@Service
public class PosTerminalServiceImpl implements PosTerminalService {

    private static final Logger log = LoggerFactory.getLogger(PosTerminalServiceImpl.class);

    private final PosTerminalRepository posTerminalRepository;
    private final AuditService auditService;

    public PosTerminalServiceImpl(PosTerminalRepository posTerminalRepository,
                                   AuditService auditService) {
        this.posTerminalRepository = posTerminalRepository;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public UUID registerTerminal(String name, UUID locationId) {
        PosTerminal terminal = new PosTerminal(name, locationId);
        terminal = posTerminalRepository.save(terminal);
        log.info("POS terminal registered: terminalId={} name={} locationId={}",
                terminal.getId(), name, locationId);
        return terminal.getId();
    }

    @Override
    @Transactional
    public void deactivateTerminal(UUID terminalId) {
        PosTerminal terminal = posTerminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("PosTerminal", terminalId));

        // Audit BEFORE mutation — architecture rule (CLAUDE.md)
        auditService.log(new AuditEntry(
                "pos_terminals",
                terminalId,
                AuditAction.STATUS_CHANGE,
                "{\"status\":\"ACTIVE\"}",
                "{\"status\":\"INACTIVE\"}",
                "system:pos-terminal-service"));

        // BusinessRuleException thrown here if already INACTIVE
        terminal.deactivate();
        posTerminalRepository.save(terminal);

        log.info("POS terminal deactivated: terminalId={}", terminalId);
    }

    @Override
    @Transactional(readOnly = true)
    public PosTerminalDto getTerminal(UUID terminalId) {
        PosTerminal terminal = posTerminalRepository.findById(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("PosTerminal", terminalId));
        return toDto(terminal);
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private PosTerminalDto toDto(PosTerminal terminal) {
        return new PosTerminalDto(
                terminal.getId(),
                terminal.getName(),
                terminal.getLocationId(),
                terminal.getStatus(),
                terminal.getLastSeenAt());
    }
}
