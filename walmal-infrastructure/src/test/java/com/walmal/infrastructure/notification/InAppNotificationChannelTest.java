package com.walmal.infrastructure.notification;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.Notification.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InAppNotificationChannelTest {

    private final InAppNotificationChannel channel = new InAppNotificationChannel();

    @Test
    void should_notThrow_when_sendIsCalled() {
        Notification notification = new Notification(
            "user-123", "Order Shipped", "Your order has been shipped.", NotificationType.IN_APP
        );

        assertThatCode(() -> channel.send(notification)).doesNotThrowAnyException();
    }

    @Test
    void should_supportInAppType_when_checked() {
        assertThat(channel.supports(NotificationType.IN_APP)).isTrue();
        assertThat(channel.supports(NotificationType.EMAIL)).isFalse();
    }
}
