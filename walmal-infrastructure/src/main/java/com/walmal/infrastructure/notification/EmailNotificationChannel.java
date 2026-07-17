package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);
    private final JavaMailSender mailSender;

    public EmailNotificationChannel(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(notification.recipient());
        message.setSubject(notification.subject());
        message.setText(notification.body());
        mailSender.send(message);
        // Mask the recipient — the raw address (incl. guest-checkout emails) is PII
        // and must not land in the log store at INFO.
        log.info("Email sent to {}: {}", maskEmail(notification.recipient()), notification.subject());
    }

    @Override
    public boolean supports(Notification.NotificationType type) {
        return type == Notification.NotificationType.EMAIL;
    }

    /** "buyer@example.com" -> "b***@example.com"; enough to correlate without exposing the address. */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
