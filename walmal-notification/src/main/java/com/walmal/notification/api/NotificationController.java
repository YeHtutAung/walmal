package com.walmal.notification.api;

import com.walmal.common.auth.AuthenticatedPrincipal;
import com.walmal.common.exception.BusinessRuleException;
import com.walmal.common.model.ApiResponse;
import com.walmal.notification.api.dto.NotificationSummaryResponse;
import com.walmal.notification.api.dto.UnreadCountResponse;
import com.walmal.notification.application.NotificationService;
import com.walmal.notification.application.dto.NotificationSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notification management")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','CASHIER','CUSTOMER')")
    @Operation(summary = "List notifications for a user")
    public ApiResponse<Page<NotificationSummaryResponse>> listNotifications(
            @PathVariable UUID userId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        verifyOwnership(userId, principal);
        Page<NotificationSummaryResponse> page = notificationService
                .listNotificationsForUser(userId, pageable)
                .map(this::toResponse);
        return ApiResponse.ok(page);
    }

    @GetMapping("/users/{userId}/unread-count")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN','STAFF','CASHIER','CUSTOMER')")
    @Operation(summary = "Count unread (sent) in-app notifications for a user")
    public ApiResponse<UnreadCountResponse> countUnread(
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        verifyOwnership(userId, principal);
        long count = notificationService.countUnread(userId);
        return ApiResponse.ok(new UnreadCountResponse(count));
    }

    private void verifyOwnership(UUID userId, AuthenticatedPrincipal principal) {
        if (!"ADMIN".equals(principal.role()) && !userId.equals(principal.userId())) {
            throw new BusinessRuleException("Access denied: you can only view your own notifications");
        }
    }

    private NotificationSummaryResponse toResponse(NotificationSummaryDto dto) {
        return new NotificationSummaryResponse(
                dto.id(), dto.type(), dto.status(), dto.subject(),
                dto.triggerEvent(), dto.referenceId(), dto.createdAt());
    }
}
