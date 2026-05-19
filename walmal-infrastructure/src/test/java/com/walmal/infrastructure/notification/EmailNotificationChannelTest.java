package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.Notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailNotificationChannel channel;

    @Test
    void should_sendEmail_when_notificationProvided() {
        Notification notification = new Notification(
            "user@example.com", "Order Confirmed", "Your order is confirmed.", NotificationType.EMAIL
        );

        channel.send(notification);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void should_supportEmailType_when_checked() {
        assertThat(channel.supports(NotificationType.EMAIL)).isTrue();
        assertThat(channel.supports(NotificationType.IN_APP)).isFalse();
    }
}
