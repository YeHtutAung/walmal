package com.walmal.notification.application.impl;

import com.walmal.common.exception.ResourceNotFoundException;
import com.walmal.notification.application.NotificationAdminService;
import com.walmal.notification.application.dto.NotificationDetailDto;
import com.walmal.notification.domain.NotificationLog;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import com.walmal.notification.infrastructure.NotificationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class NotificationAdminServiceImpl implements NotificationAdminService {

    private final NotificationLogRepository repo;

    public NotificationAdminServiceImpl(NotificationLogRepository repo) {
        this.repo = repo;
    }

    @Override
    public Page<NotificationDetailDto> listAll(@Nullable NotificationType type,
                                                @Nullable NotificationStatus status,
                                                Pageable pageable) {
        return repo.findByFilters(type, status, pageable).map(this::toDto);
    }

    @Override
    public NotificationDetailDto getById(UUID id) {
        NotificationLog n = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        return toDto(n);
    }

    private NotificationDetailDto toDto(NotificationLog n) {
        return new NotificationDetailDto(
                n.getId(), n.getRecipientId(), n.getType(), n.getStatus(),
                n.getSubject(), n.getBody(), n.getErrorMessage(),
                n.getTriggerEvent(), n.getReferenceId(),
                n.getCreatedAt(), n.getUpdatedAt());
    }
}
