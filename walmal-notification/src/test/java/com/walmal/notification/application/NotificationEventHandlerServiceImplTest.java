package com.walmal.notification.application;

import com.walmal.auth.application.StaffNotificationQueryService;
import com.walmal.notification.application.impl.NotificationEventHandlerServiceImpl;
import com.walmal.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationEventHandlerServiceImplTest {

    @Mock NotificationService notificationService;
    @Mock StaffNotificationQueryService staffQueryService;

    NotificationEventHandlerServiceImpl handler;

    private UUID orderId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        handler = new NotificationEventHandlerServiceImpl(notificationService, staffQueryService);
        orderId = UUID.randomUUID();
        userId  = UUID.randomUUID();
    }

    @Test
    @DisplayName("should_sendEmailAndInApp_when_orderConfirmed")
    void should_sendEmailAndInApp_when_orderConfirmed() {
        handler.handleOrderConfirmed(orderId, userId);

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), anyString(), anyString(),
                eq("order.confirmed"), eq(orderId));
        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.IN_APP), anyString(), anyString(),
                eq("order.confirmed"), eq(orderId));
    }

    @Test
    @DisplayName("should_sendGenericCancellationNote_when_reasonIsNull")
    void should_sendGenericCancellationNote_when_reasonIsNull() {
        handler.handleOrderCancelled(orderId, userId, null);

        verify(notificationService, times(2)).sendNotification(
                eq(userId), any(NotificationType.class), anyString(), anyString(),
                eq("order.cancelled"), eq(orderId));
    }

    @Test
    @DisplayName("should_sendConflictSpecificNote_when_reasonIsPOS_PRIORITY")
    void should_sendConflictSpecificNote_when_reasonIsPOS_PRIORITY() {
        handler.handleOrderCancelled(orderId, userId, "POS_PRIORITY");

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), anyString(),
                contains("in-store sale was processed earlier"),
                eq("order.cancelled"), eq(orderId));
    }

    @Test
    @DisplayName("should_sendConflictSpecificNote_when_reasonIsBUFFER_EXHAUSTED")
    void should_sendConflictSpecificNote_when_reasonIsBUFFER_EXHAUSTED() {
        handler.handleOrderCancelled(orderId, userId, "BUFFER_EXHAUSTED");

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), anyString(),
                contains("simultaneously sold"),
                eq("order.cancelled"), eq(orderId));
    }

    @Test
    @DisplayName("should_sendEmailAndInApp_when_fulfillmentShipped")
    void should_sendEmailAndInApp_when_fulfillmentShipped() {
        handler.handleFulfillmentShipped(orderId, userId, "FedEx", "TRK-001");

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), anyString(),
                contains("FedEx"), eq("warehouse.fulfillment.shipped"), eq(orderId));
        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.IN_APP), anyString(),
                contains("TRK-001"), eq("warehouse.fulfillment.shipped"), eq(orderId));
    }

    @Test
    @DisplayName("should_notifyStaffAndAdmins_when_stockLow")
    void should_notifyStaffAndAdmins_when_stockLow() {
        UUID staffId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        when(staffQueryService.findActiveUserIdsByRole("STAFF")).thenReturn(List.of(staffId));
        when(staffQueryService.findActiveUserIdsByRole("ADMIN")).thenReturn(List.of(adminId));

        handler.handleStockLow(variantId, locationId, 3, 10);

        verify(notificationService).sendNotification(
                eq(staffId), eq(NotificationType.IN_APP), anyString(), anyString(),
                eq("inventory.stock.low"), eq(variantId));
        verify(notificationService).sendNotification(
                eq(adminId), eq(NotificationType.IN_APP), anyString(), anyString(),
                eq("inventory.stock.low"), eq(variantId));
    }

    @Test
    @DisplayName("should_queryStaffBeforeSending_when_stockLow")
    void should_queryStaffBeforeSending_when_stockLow() {
        UUID staffId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        when(staffQueryService.findActiveUserIdsByRole("STAFF")).thenReturn(List.of(staffId));
        when(staffQueryService.findActiveUserIdsByRole("ADMIN")).thenReturn(List.of());

        handler.handleStockLow(variantId, UUID.randomUUID(), 3, 10);

        InOrder inOrder = inOrder(staffQueryService, notificationService);
        inOrder.verify(staffQueryService).findActiveUserIdsByRole("STAFF");
        inOrder.verify(staffQueryService).findActiveUserIdsByRole("ADMIN");
        inOrder.verify(notificationService).sendNotification(
                eq(staffId), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should_sendWelcomeEmail_when_userRegistered")
    void should_sendWelcomeEmail_when_userRegistered() {
        handler.handleUserRegistered(userId, "alice", "alice@walmal.com");

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), contains("Welcome"), anyString(),
                eq("auth.user.registered"), eq(userId));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("should_sendEmailAndInApp_when_posSyncConflictResolved")
    void should_sendEmailAndInApp_when_posSyncConflictResolved() {
        UUID cancelledOrderId = UUID.randomUUID();
        handler.handlePosSyncConflictResolved(cancelledOrderId, userId, "POS_PRIORITY");

        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.EMAIL), anyString(),
                contains("conflict"), eq("pos.sync.conflict.resolved"), eq(cancelledOrderId));
        verify(notificationService).sendNotification(
                eq(userId), eq(NotificationType.IN_APP), anyString(),
                contains("conflict"), eq("pos.sync.conflict.resolved"), eq(cancelledOrderId));
    }
}
