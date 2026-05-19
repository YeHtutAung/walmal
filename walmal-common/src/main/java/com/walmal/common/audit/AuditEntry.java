package com.walmal.common.audit;

import java.util.UUID;

public record AuditEntry(
    String tableName,
    UUID recordId,
    AuditAction action,
    String oldValue,
    String newValue,
    String performedBy
) {}
