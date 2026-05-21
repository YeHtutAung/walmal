package com.walmal.notification.api;

import com.walmal.auth.application.TokenValidationService;
import com.walmal.auth.config.AuthSecurityConfig;
import com.walmal.auth.config.JwtProperties;
import com.walmal.notification.application.NotificationService;
import com.walmal.notification.application.dto.NotificationSummaryDto;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationController.class)
@Import({AuthSecurityConfig.class, NotificationExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "walmal.jwt.secret=test-secret-key-for-notification-controller-tests-min32",
        "walmal.jwt.access-token-expire-minutes=15"
})
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean NotificationService notificationService;
    @MockitoBean TokenValidationService tokenValidationService;

    private static final UUID USER_ID = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken customerAuth() {
        return new UsernamePasswordAuthenticationToken(
                "customer", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "pw",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private NotificationSummaryDto buildDto() {
        return new NotificationSummaryDto(
                UUID.randomUUID(), USER_ID, NotificationType.IN_APP,
                NotificationStatus.SENT, "Order confirmed", "order.confirmed",
                UUID.randomUUID(), Instant.now());
    }

    @Test
    @DisplayName("should_return200_when_listNotificationsForUser_asCustomer")
    void should_return200_when_listNotificationsForUser_asCustomer() throws Exception {
        when(notificationService.listNotificationsForUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildDto())));

        mockMvc.perform(get("/api/v1/notifications/users/{userId}", USER_ID)
                        .with(authentication(customerAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].type").value("IN_APP"));
    }

    @Test
    @DisplayName("should_return200_when_countUnread_asAdmin")
    void should_return200_when_countUnread_asAdmin() throws Exception {
        when(notificationService.countUnread(USER_ID)).thenReturn(3L);

        mockMvc.perform(get("/api/v1/notifications/users/{userId}/unread-count", USER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));
    }

    @Test
    @DisplayName("should_return401_when_unauthenticated")
    void should_return401_when_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/users/{userId}", USER_ID))
                .andExpect(status().isUnauthorized());
    }
}
