package com.walmal.infrastructure.audit;

import com.walmal.common.audit.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditEntry entry) {
        AuditLog auditLog = new AuditLog(
            entry.tableName(),
            entry.recordId(),
            entry.action(),
            entry.oldValue(),
            entry.newValue(),
            entry.performedBy()
        );
        auditLogRepository.save(auditLog);
    }
}
