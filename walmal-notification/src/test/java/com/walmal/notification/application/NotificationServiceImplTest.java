package com.walmal.notification.application;

import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import com.walmal.notification.application.dto.NotificationSummaryDto;
import com.walmal.notification.application.impl.NotificationServiceImpl;
import com.walmal.notification.domain.NotificationLog;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import com.walmal.notification.infrastructure.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock NotificationLogRepository notificationLogRepository;
    @Mock NotificationChannel emailChannel;
    @Mock NotificationChannel inAppChannel;

    NotificationServiceImpl service;

    private UUID recipientId;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationLogRepository, emailChannel, inAppChannel);
        recipientId = UUID.randomUUID();
        when(notificationLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailChannel.supports(Notification.NotificationType.EMAIL)).thenReturn(true);
        when(inAppChannel.supports(Notification.NotificationType.IN_APP)).thenReturn(true);
    }

    @Test
    @DisplayName("should_persistAndSendEmail_when_emailNotificationRequested")
    void should_persistAndSendEmail_when_emailNotificationRequested() {
        service.sendNotification(recipientId, NotificationType.EMAIL, "Subject", "Body",
                "order.confirmed", UUID.randomUUID());

        verify(emailChannel).send(any(Notification.class));
        verifyNoInteractions(inAppChannel);
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("should_persistAndSendInApp_when_inAppNotificationRequested")
    void should_persistAndSendInApp_when_inAppNotificationRequested() {
        service.sendNotification(recipientId, NotificationType.IN_APP, "Subject", "Body",
                "order.confirmed", UUID.randomUUID());

        verify(inAppChannel).send(any(Notification.class));
        verifyNoInteractions(emailChannel);
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    @DisplayName("should_markFailed_when_channelThrows")
    void should_markFailed_when_channelThrows() {
        doThrow(new RuntimeException("SMTP error")).when(emailChannel).send(any());

        service.sendNotification(recipientId, NotificationType.EMAIL, "Subject", "Body",
                "order.confirmed", UUID.randomUUID());

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository, times(2)).save(captor.capture());
        NotificationLog lastSaved = captor.getAllValues().get(1);
        assertThat(lastSaved.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(lastSaved.getErrorMessage()).contains("SMTP error");
    }

    @Test
    @DisplayName("should_persistThenSendThenPersistAgain_in_correctOrder")
    void should_persistThenSendThenPersistAgain_in_correctOrder() {
        service.sendNotification(recipientId, NotificationType.IN_APP, "Subject", "Body",
                "order.confirmed", UUID.randomUUID());

        // Verify: save → send → save (in that order)
        InOrder inOrder = inOrder(notificationLogRepository, inAppChannel);
        inOrder.verify(notificationLogRepository).save(any(NotificationLog.class));
        inOrder.verify(inAppChannel).send(any(Notification.class));
        inOrder.verify(notificationLogRepository).save(any(NotificationLog.class));

        // Final state is SENT
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("should_listNotificationsForUser")
    void should_listNotificationsForUser() {
        NotificationLog log = new NotificationLog(recipientId, NotificationType.IN_APP,
                "Subject", "Body", "order.confirmed", UUID.randomUUID());
        when(notificationLogRepository.findByRecipientIdOrderByCreatedAtDesc(eq(recipientId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        Page<NotificationSummaryDto> result = service.listNotificationsForUser(recipientId, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).recipientId()).isEqualTo(recipientId);
    }

    @Test
    @DisplayName("should_returnUnreadCount")
    void should_returnUnreadCount() {
        when(notificationLogRepository.countByRecipientIdAndStatus(recipientId, NotificationStatus.SENT))
                .thenReturn(5L);

        long count = service.countUnread(recipientId);

        assertThat(count).isEqualTo(5L);
    }
}
