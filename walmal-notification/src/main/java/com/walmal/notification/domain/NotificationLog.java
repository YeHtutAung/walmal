package com.walmal.notification.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "notification_log")
public class NotificationLog extends BaseEntity {

    // nullable — guest notifications have no user account
    @Column(name = "recipient_id")
    private UUID recipientId;

    // set only when recipientId is null (guest)
    @Column(name = "recipient_email", length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "trigger_event", nullable = false, length = 100)
    private String triggerEvent;

    @Column(name = "reference_id")
    private UUID referenceId;

    protected NotificationLog() {}

    public NotificationLog(UUID recipientId, NotificationType type, String subject,
                           String body, String triggerEvent, UUID referenceId) {
        this.recipientId = recipientId;
        this.type = type;
        this.subject = subject;
        this.body = body;
        this.triggerEvent = triggerEvent;
        this.referenceId = referenceId;
        this.status = NotificationStatus.PENDING;
    }

    /** Guest notification: recipient identified by email; recipientId stays null. */
    public NotificationLog(String recipientEmail, NotificationType type, String subject,
                           String body, String triggerEvent, UUID referenceId) {
        this.recipientEmail = recipientEmail;
        this.type = type;
        this.subject = subject;
        this.body = body;
        this.triggerEvent = triggerEvent;
        this.referenceId = referenceId;
        this.status = NotificationStatus.PENDING;
    }

    public void markSent() { this.status = NotificationStatus.SENT; }

    public void markFailed(String error) {
        this.status = NotificationStatus.FAILED;
        this.errorMessage = error;
    }

    public UUID getRecipientId() { return recipientId; }
    public String getRecipientEmail() { return recipientEmail; }
    public NotificationType getType() { return type; }
    public NotificationStatus getStatus() { return status; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getErrorMessage() { return errorMessage; }
    public String getTriggerEvent() { return triggerEvent; }
    public UUID getReferenceId() { return referenceId; }
}
