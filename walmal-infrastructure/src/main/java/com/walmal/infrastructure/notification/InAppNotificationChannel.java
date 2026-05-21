package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnMissingBean(name = "inAppNotificationChannelImpl")
public class InAppNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationChannel.class);

    @Override
    public void send(Notification notification) {
        // TODO: Persist to in-app notification table when Notification module is built
        log.info("In-app notification for {}: {}", notification.recipient(), notification.subject());
    }

    @Override
    public boolean supports(Notification.NotificationType type) {
        return type == Notification.NotificationType.IN_APP;
    }
}
