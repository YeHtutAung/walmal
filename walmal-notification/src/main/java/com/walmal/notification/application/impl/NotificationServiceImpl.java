package com.walmal.notification.application.impl;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import com.walmal.notification.application.NotificationService;
import com.walmal.notification.application.dto.NotificationSummaryDto;
import com.walmal.notification.domain.NotificationLog;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import com.walmal.notification.infrastructure.NotificationLogRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationChannel emailChannel;
    private final NotificationChannel inAppChannel;

    public NotificationServiceImpl(
            NotificationLogRepository notificationLogRepository,
            @Qualifier("emailNotificationChannel") NotificationChannel emailChannel,
            @Qualifier("inAppNotificationChannelImpl") NotificationChannel inAppChannel) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailChannel = emailChannel;
        this.inAppChannel = inAppChannel;
    }

    @Override
    public void sendNotification(UUID recipientId, NotificationType type, String subject,
                                 String body, String triggerEvent, UUID referenceId) {
        NotificationLog log = new NotificationLog(recipientId, type, subject, body, triggerEvent, referenceId);
        notificationLogRepository.save(log);

        try {
            Notification notification = new Notification(
                    recipientId.toString(),
                    subject,
                    body,
                    type == NotificationType.EMAIL
                            ? Notification.NotificationType.EMAIL
                            : Notification.NotificationType.IN_APP
            );

            if (type == NotificationType.EMAIL) {
                emailChannel.send(notification);
            } else {
                inAppChannel.send(notification);
            }
            log.markSent();
            notificationLogRepository.save(log);
        } catch (Exception e) {
            log.markFailed(e.getMessage());
            notificationLogRepository.save(log);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationSummaryDto> listNotificationsForUser(UUID userId, Pageable pageable) {
        return notificationLogRepository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
                .map(n -> new NotificationSummaryDto(
                        n.getId(), n.getRecipientId(), n.getType(), n.getStatus(),
                        n.getSubject(), n.getTriggerEvent(), n.getReferenceId(), n.getCreatedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationLogRepository.countByRecipientIdAndStatus(userId, NotificationStatus.SENT);
    }
}
