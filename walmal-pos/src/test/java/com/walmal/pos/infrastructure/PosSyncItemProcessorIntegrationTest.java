package com.walmal.pos.infrastructure;

import com.walmal.pos.domain.PosTerminal;
import com.walmal.pos.domain.PosSyncQueue;
import com.walmal.pos.domain.QueueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataJpa integration test verifying that pos_sync_queue rows are only soft-completed
 * (PROCESSED or FAILED) and are never hard-deleted through repository operations.
 *
 * <p>This test validates the architecture invariant: PosSyncQueue rows must never
 * be hard-deleted — they form an append-only operational audit log.</p>
 *
 * <p>Tagged {@code "integration"} so it runs only with {@code mvn test -Dgroups=integration}.</p>
 */
@Tag("integration")
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
class PosSyncItemProcessorIntegrationTest {

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

    @Autowired PosTerminalRepository posTerminalRepository;
    @Autowired PosSyncQueueRepository posSyncQueueRepository;

    @Test
    @DisplayName("should_persistPendingQueueRow_and_transitionToProcessed_not_delete")
    void should_persistPendingQueueRow_and_transitionToProcessed_not_delete() {
        // Create a terminal
        PosTerminal terminal = new PosTerminal("Queue Test Terminal",
                UUID.fromString("L0000000-0000-0000-0000-000000000001"));
        terminal = posTerminalRepository.save(terminal);

        // Create a queue row
        PosSyncQueue queueRow = new PosSyncQueue(terminal, "{\"localId\":\"test\"}");
        queueRow = posSyncQueueRepository.save(queueRow);
        UUID queueId = queueRow.getId();

        assertThat(queueRow.getStatus()).isEqualTo(QueueStatus.PENDING);

        // Transition to PROCESSED (soft-complete)
        queueRow.markProcessed();
        posSyncQueueRepository.save(queueRow);

        // Row must still exist — only status changed
        PosSyncQueue reloaded = posSyncQueueRepository.findById(queueId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueueStatus.PROCESSED);
        assertThat(reloaded.getProcessedAt()).isNotNull();

        // Total count should still be 1 — no hard delete
        long count = posSyncQueueRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("should_persistFailedQueueRow_when_markFailed_called_not_delete")
    void should_persistFailedQueueRow_when_markFailed_called_not_delete() {
        PosTerminal terminal = new PosTerminal("Failed Queue Terminal",
                UUID.fromString("L0000000-0000-0000-0000-000000000001"));
        terminal = posTerminalRepository.save(terminal);

        PosSyncQueue queueRow = new PosSyncQueue(terminal, "{\"localId\":\"test-fail\"}");
        queueRow = posSyncQueueRepository.save(queueRow);
        UUID queueId = queueRow.getId();

        // Simulate failure transition
        queueRow.markFailed("Inventory service unavailable");
        posSyncQueueRepository.save(queueRow);

        // Row must still exist with FAILED status
        PosSyncQueue reloaded = posSyncQueueRepository.findById(queueId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QueueStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("Inventory service unavailable");
        assertThat(reloaded.getProcessedAt()).isNull();  // not processed
    }

    @Test
    @DisplayName("should_findPendingQueueRows_by_terminal_and_status")
    void should_findPendingQueueRows_by_terminal_and_status() {
        PosTerminal terminal = new PosTerminal("Status Query Terminal",
                UUID.fromString("L0000000-0000-0000-0000-000000000001"));
        terminal = posTerminalRepository.save(terminal);

        // Two PENDING rows
        posSyncQueueRepository.save(new PosSyncQueue(terminal, "{\"localId\":\"p1\"}"));
        posSyncQueueRepository.save(new PosSyncQueue(terminal, "{\"localId\":\"p2\"}"));

        // One FAILED row
        PosSyncQueue failedRow = new PosSyncQueue(terminal, "{\"localId\":\"f1\"}");
        failedRow.markFailed("test failure");
        posSyncQueueRepository.save(failedRow);

        List<PosSyncQueue> pendingRows = posSyncQueueRepository
                .findByTerminalIdAndStatus(terminal.getId(), QueueStatus.PENDING);
        assertThat(pendingRows).hasSize(2);

        long pendingCount = posSyncQueueRepository
                .countByTerminalIdAndStatus(terminal.getId(), QueueStatus.PENDING);
        assertThat(pendingCount).isEqualTo(2);

        long failedCount = posSyncQueueRepository
                .countByTerminalIdAndStatus(terminal.getId(), QueueStatus.FAILED);
        assertThat(failedCount).isEqualTo(1);
    }
}
