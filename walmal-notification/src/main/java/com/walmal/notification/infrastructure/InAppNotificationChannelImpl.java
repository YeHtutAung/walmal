package com.walmal.notification.infrastructure;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service("inAppNotificationChannelImpl")
@Primary
public class InAppNotificationChannelImpl implements NotificationChannel {

    @Override
    public void send(Notification notification) {
        // Persistence is handled by NotificationServiceImpl (saves NotificationLog).
        // This bean exists to satisfy @Qualifier("inAppNotificationChannelImpl")
        // and to displace the stub in walmal-infrastructure via @Primary + bean name.
    }

    @Override
    public boolean supports(Notification.NotificationType type) {
        return type == Notification.NotificationType.IN_APP;
    }
}
