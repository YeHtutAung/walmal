package com.walmal.common.notification;

public interface NotificationChannel {
    void send(Notification notification);
    boolean supports(Notification.NotificationType type);
}
