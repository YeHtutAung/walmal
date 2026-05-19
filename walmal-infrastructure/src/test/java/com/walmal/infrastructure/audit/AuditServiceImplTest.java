package com.walmal.infrastructure.audit;

import com.walmal.common.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditServiceImpl auditService;

    @Test
    void should_persistAuditLog_when_logIsCalled() {
        AuditEntry entry = new AuditEntry(
            "order_items", UUID.randomUUID(), AuditAction.DELETE,
            "{\"qty\":5}", null, "admin"
        );

        auditService.log(entry);

        verify(auditLogRepository).save(any(AuditLog.class));
    }
}
