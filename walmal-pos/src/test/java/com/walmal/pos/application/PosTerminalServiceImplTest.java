package com.walmal.pos.application;

import com.walmal.common.audit.AuditEntry;
import com.walmal.common.audit.AuditService;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.pos.application.dto.PosTerminalDto;
import com.walmal.pos.application.impl.PosTerminalServiceImpl;
import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.TerminalStatus;
import com.walmal.pos.infrastructure.PosTerminalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosTerminalServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class PosTerminalServiceImplTest {

    @Mock PosTerminalRepository posTerminalRepository;
    @Mock AuditService auditService;

    @InjectMocks PosTerminalServiceImpl service;

    private UUID terminalId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        terminalId = UUID.randomUUID();
        locationId = UUID.randomUUID();
    }

    @Test
    @DisplayName("should_registerTerminal_successfully")
    void should_registerTerminal_successfully() {
        PosTerminal saved = new PosTerminal("Store A Terminal 1", locationId);
        when(posTerminalRepository.save(any(PosTerminal.class))).thenAnswer(inv -> {
            PosTerminal t = inv.getArgument(0);
            return t;
        });

        UUID id = service.registerTerminal("Store A Terminal 1", locationId);

        verify(posTerminalRepository).save(any(PosTerminal.class));
        // AuditService must NOT be called on registration (INSERT is not destructive)
        verify(auditService, never()).log(any());
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_terminalNotFound_on_deactivate")
    void should_throwResourceNotFoundException_when_terminalNotFound_on_deactivate() {
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateTerminal(terminalId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(auditService, never()).log(any());
    }

    @Test
    @DisplayName("should_throwBusinessRuleException_when_deactivatingAlreadyInactiveTerminal")
    void should_throwBusinessRuleException_when_deactivatingAlreadyInactiveTerminal() {
        PosTerminal terminal = new PosTerminal("Store A Terminal 1", locationId);
        terminal.deactivate();   // already INACTIVE

        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service.deactivateTerminal(terminalId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already INACTIVE");
    }

    @Test
    @DisplayName("should_writeAuditLog_before_deactivating")
    void should_writeAuditLog_before_deactivating() {
        PosTerminal terminal = new PosTerminal("Store A Terminal 1", locationId);
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));
        when(posTerminalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InOrder inOrder = inOrder(auditService, posTerminalRepository);

        service.deactivateTerminal(terminalId);

        inOrder.verify(auditService).log(any(AuditEntry.class));
        inOrder.verify(posTerminalRepository).save(any());
    }

    @Test
    @DisplayName("should_returnTerminalDto_when_terminalFound")
    void should_returnTerminalDto_when_terminalFound() {
        PosTerminal terminal = new PosTerminal("Store A Terminal 1", locationId);
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.of(terminal));

        PosTerminalDto dto = service.getTerminal(terminalId);

        assertThat(dto.name()).isEqualTo("Store A Terminal 1");
        assertThat(dto.locationId()).isEqualTo(locationId);
        assertThat(dto.status()).isEqualTo(TerminalStatus.ACTIVE);
    }

    @Test
    @DisplayName("should_throwResourceNotFoundException_when_terminalNotFound_on_get")
    void should_throwResourceNotFoundException_when_terminalNotFound_on_get() {
        when(posTerminalRepository.findById(terminalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTerminal(terminalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
