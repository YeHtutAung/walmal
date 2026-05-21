package com.walmal.notification.infrastructure;

import com.walmal.auth.application.StaffNotificationQueryService;
import com.walmal.common.audit.AuditService;
import com.walmal.common.cache.CacheService;
import com.walmal.common.cache.DistributedLockService;
import com.walmal.common.event.DomainEvent;
import com.walmal.common.event.DomainEventPublisher;
import com.walmal.common.notification.Notification;
import com.walmal.common.notification.NotificationChannel;
import com.walmal.common.storage.FileStorageService;
import com.walmal.common.storage.StoredFile;
import com.walmal.notification.application.NotificationService;
import com.walmal.notification.application.dto.NotificationSummaryDto;
import com.walmal.notification.domain.NotificationStatus;
import com.walmal.notification.domain.NotificationType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("walmal_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired NotificationService notificationService;
    @Autowired NotificationLogRepository notificationLogRepository;

    private static final UUID TEST_USER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    @Test
    void should_persistNotification_when_emailSent() {
        notificationService.sendNotification(TEST_USER_ID, NotificationType.EMAIL,
                "Test subject", "Test body", "order.confirmed", UUID.randomUUID());

        Page<NotificationSummaryDto> page = notificationService
                .listNotificationsForUser(TEST_USER_ID, Pageable.unpaged());

        assertThat(page.getContent()).isNotEmpty();
        NotificationSummaryDto first = page.getContent().get(0);
        assertThat(first.type()).isEqualTo(NotificationType.EMAIL);
        assertThat(first.triggerEvent()).isEqualTo("order.confirmed");
    }

    @Test
    void should_markSent_when_channelSucceeds() {
        notificationService.sendNotification(TEST_USER_ID, NotificationType.IN_APP,
                "In-app subject", "In-app body", "order.confirmed", UUID.randomUUID());

        Page<NotificationSummaryDto> page = notificationService
                .listNotificationsForUser(TEST_USER_ID, Pageable.unpaged());

        assertThat(page.getContent()).anyMatch(n -> n.status() == NotificationStatus.SENT);
    }

    @Test
    void should_countUnread_when_notificationsExist() {
        notificationService.sendNotification(TEST_USER_ID, NotificationType.IN_APP,
                "Unread test", "Body", "order.confirmed", UUID.randomUUID());

        long unread = notificationService.countUnread(TEST_USER_ID);
        assertThat(unread).isGreaterThan(0);
    }

    @TestConfiguration
    static class TestInfrastructureConfig {

        @Bean @Primary
        AuditService noOpAuditService() {
            return entry -> {};
        }

        @Bean @Primary
        CacheService noOpCacheService() {
            return new CacheService() {
                @Override public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }
                @Override public <T> void put(String key, T value) {}
                @Override public <T> void put(String key, T value, Duration ttl) {}
                @Override public void evict(String key) {}
                @Override public void evictByPrefix(String prefix) {}
            };
        }

        @Bean @Primary
        DomainEventPublisher noOpEventPublisher() {
            return new DomainEventPublisher() {
                @Override public void publish(DomainEvent event) {}
                @Override public void publish(DomainEvent event, String routingKey) {}
            };
        }

        @Bean @Primary
        FileStorageService noOpFileStorageService() {
            return new FileStorageService() {
                @Override public StoredFile upload(String b, String k, InputStream c, String ct, long s) {
                    return new StoredFile(k, b, ct, s);
                }
                @Override public InputStream download(String b, String k) { return InputStream.nullInputStream(); }
                @Override public void delete(String b, String k) {}
                @Override public String getPresignedUrl(String b, String k) { return "http://test/" + k; }
            };
        }

        @Bean @Primary
        DistributedLockService noOpLockService() {
            return new DistributedLockService() {
                @Override public boolean tryLock(String key, Duration t) { return true; }
                @Override public void unlock(String key) {}
                @Override public <T> T executeWithLock(String key, Duration t, Supplier<T> a) { return a.get(); }
            };
        }

        @Bean @Primary
        StaffNotificationQueryService stubStaffQueryService() {
            return role -> List.of();
        }

        @Bean("emailNotificationChannel") @Primary
        NotificationChannel stubEmailChannel() {
            return new NotificationChannel() {
                @Override public void send(Notification n) {}
                @Override public boolean supports(Notification.NotificationType t) {
                    return t == Notification.NotificationType.EMAIL;
                }
            };
        }
    }
}
