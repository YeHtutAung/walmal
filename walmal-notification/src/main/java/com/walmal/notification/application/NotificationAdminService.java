package com.walmal.notification.application;

import com.walmal.notification.application.dto.NotificationDetailDto;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Admin read-only view of notification logs.
 * Segregated from {@link NotificationService} (user-facing concerns).
 */
public interface NotificationAdminService {

    Page<NotificationDetailDto> listAll(@Nullable NotificationType type,
                                        @Nullable NotificationStatus status,
                                        Pageable pageable);

    NotificationDetailDto getById(UUID id);
}
