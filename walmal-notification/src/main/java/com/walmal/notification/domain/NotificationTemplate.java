package com.walmal.notification.domain;

import com.walmal.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_templates")
public class NotificationTemplate extends BaseEntity {

    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    protected NotificationTemplate() {}

    public String getEventKey() { return eventKey; }
    public NotificationType getType() { return type; }
    public String getSubject() { return subject; }
    public String getBodyTemplate() { return bodyTemplate; }
    public boolean isActive() { return isActive; }
}
