package com.walmal.notification.application.impl;

import com.walmal.auth.application.StaffNotificationQueryService;
import com.walmal.notification.application.NotificationEventHandlerService;
import com.walmal.notification.application.NotificationService;
import com.walmal.notification.domain.NotificationType;
import com.walmal.order.application.GuestOrderQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationEventHandlerServiceImpl implements NotificationEventHandlerService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandlerServiceImpl.class);

    private static final String RK_ORDER_CONFIRMED      = "order.confirmed";
    private static final String RK_ORDER_CANCELLED      = "order.cancelled";
    private static final String RK_FULFILLMENT_SHIPPED  = "warehouse.fulfillment.shipped";
    private static final String RK_STOCK_LOW            = "inventory.stock.low";
    private static final String RK_USER_REGISTERED      = "auth.user.registered";
    private static final String RK_POS_CONFLICT         = "pos.sync.conflict.resolved";

    private final NotificationService notificationService;
    private final StaffNotificationQueryService staffQueryService;
    private final GuestOrderQueryService guestOrderQueryService;

    public NotificationEventHandlerServiceImpl(NotificationService notificationService,
                                               StaffNotificationQueryService staffQueryService,
                                               GuestOrderQueryService guestOrderQueryService) {
        this.notificationService = notificationService;
        this.staffQueryService = staffQueryService;
        this.guestOrderQueryService = guestOrderQueryService;
    }

    @Override
    public void handleOrderConfirmed(UUID orderId, UUID userId) {
        String subject = "Your order has been confirmed";
        String body = "Your order " + orderId + " has been confirmed and is being processed.";
        if (handledAsGuest(userId, orderId, subject, body, RK_ORDER_CONFIRMED)) return;
        notificationService.sendNotification(userId, NotificationType.EMAIL, subject, body,
                RK_ORDER_CONFIRMED, orderId);
        notificationService.sendNotification(userId, NotificationType.IN_APP,
                "Order confirmed", "Your order " + orderId + " has been confirmed.",
                RK_ORDER_CONFIRMED, orderId);
    }

    @Override
    public void handleOrderCancelled(UUID orderId, UUID userId, String cancellationReason) {
        String note = buildCancellationNote(cancellationReason);
        String subject = "Your order has been cancelled";
        String emailBody = "Your order " + orderId + " has been cancelled. " + note;
        String inAppBody = "Your order " + orderId + " has been cancelled. " + note;
        if (handledAsGuest(userId, orderId, subject, emailBody, RK_ORDER_CANCELLED)) return;
        notificationService.sendNotification(userId, NotificationType.EMAIL, subject, emailBody,
                RK_ORDER_CANCELLED, orderId);
        notificationService.sendNotification(userId, NotificationType.IN_APP,
                "Order cancelled", inAppBody, RK_ORDER_CANCELLED, orderId);
    }

    @Override
    public void handleFulfillmentShipped(UUID orderId, UUID userId, String carrier, String trackingNumber) {
        String subject = "Your order has been shipped";
        String body = "Your order " + orderId + " has been shipped via " + carrier
                + ". Tracking: " + trackingNumber;
        if (handledAsGuest(userId, orderId, subject, body, RK_FULFILLMENT_SHIPPED)) return;
        notificationService.sendNotification(userId, NotificationType.EMAIL, subject, body,
                RK_FULFILLMENT_SHIPPED, orderId);
        notificationService.sendNotification(userId, NotificationType.IN_APP,
                "Order shipped", "Your order " + orderId + " has been shipped. Tracking: " + trackingNumber,
                RK_FULFILLMENT_SHIPPED, orderId);
    }

    @Override
    public void handleStockLow(UUID variantId, UUID locationId, int availableQuantity, int threshold) {
        String subject = "Low stock alert";
        String body = "Variant " + variantId + " at location " + locationId
                + " is below threshold: " + availableQuantity + " remaining (threshold: " + threshold + ").";

        List<UUID> staffIds = staffQueryService.findActiveUserIdsByRole("STAFF");
        List<UUID> adminIds = staffQueryService.findActiveUserIdsByRole("ADMIN");

        for (UUID recipientId : staffIds) {
            notificationService.sendNotification(recipientId, NotificationType.IN_APP,
                    subject, body, RK_STOCK_LOW, variantId);
        }
        for (UUID recipientId : adminIds) {
            notificationService.sendNotification(recipientId, NotificationType.IN_APP,
                    subject, body, RK_STOCK_LOW, variantId);
        }
    }

    @Override
    public void handleUserRegistered(UUID userId, String username, String email) {
        String subject = "Welcome to Walmal";
        String body = "Hi " + username + ", welcome to Walmal! Your account has been created successfully.";
        notificationService.sendNotification(userId, NotificationType.EMAIL, subject, body,
                RK_USER_REGISTERED, userId);
    }

    @Override
    public void handlePosSyncConflictResolved(UUID cancelledOrderId, UUID userId, String conflictReason) {
        if (isGuest(userId, RK_POS_CONFLICT, cancelledOrderId)) return;
        String subject = "Your order was affected by a POS sync conflict";
        String body = "Your order " + cancelledOrderId
                + " was affected by an in-store sale conflict and has been cancelled."
                + " We apologize for the inconvenience.";
        notificationService.sendNotification(userId, NotificationType.EMAIL, subject, body,
                RK_POS_CONFLICT, cancelledOrderId);
        notificationService.sendNotification(userId, NotificationType.IN_APP,
                "Order cancelled due to POS conflict", body, RK_POS_CONFLICT, cancelledOrderId);
    }

    /**
     * Guest branch: EMAIL-only to the order's guest_email. Returns true if the
     * event was handled as a guest order (caller must return immediately).
     */
    private boolean handledAsGuest(UUID userId, UUID orderId, String subject, String body, String triggerEvent) {
        if (userId != null) return false;
        guestOrderQueryService.findGuestEmailByOrderId(orderId).ifPresentOrElse(
                email -> notificationService.sendGuestEmailNotification(email, subject, body, triggerEvent, orderId),
                () -> log.debug("Skipping {} notification for order {}: no guest email on record", triggerEvent, orderId));
        return true;
    }

    /**
     * Guest orders (V13: nullable user_id) get no POS-conflict notification:
     * there is no user account for in-app delivery, and the guest cancellation
     * email already carries the conflict note, so skip instead.
     */
    private boolean isGuest(UUID userId, String triggerEvent, UUID referenceId) {
        if (userId != null) return false;
        log.debug("Skipping {} notification for guest order {} (no user account)", triggerEvent, referenceId);
        return true;
    }

    private String buildCancellationNote(String reason) {
        if (reason == null) return "";
        return switch (reason.toUpperCase()) {
            case "POS_PRIORITY"      -> "An in-store sale was processed earlier for the same item.";
            case "BUFFER_EXHAUSTED"  -> "The item was simultaneously sold in-store and online.";
            default                  -> "";
        };
    }
}
