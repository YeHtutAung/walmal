package com.walmal.common.notification;

public record Notification(
    String recipient,
    String subject,
    String body,
    NotificationType type
) {
    public enum NotificationType {
        EMAIL, IN_APP
    }
}
