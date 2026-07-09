package com.walmal.infrastructure.messaging;

import com.walmal.common.event.DomainEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RabbitDomainEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private RabbitDomainEventPublisher publisher;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void should_sendToRabbitMQ_when_eventPublished() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created"),
            eq(event)
        );
    }

    @Test
    void should_useCustomRoutingKey_when_provided() {
        DomainEvent event = new DomainEvent("order.created") {};

        publisher.publish(event, "order.created.priority");

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created.priority"),
            eq(event)
        );
    }

    @Test
    void should_deferPublishUntilAfterCommit_when_transactionActive() {
        DomainEvent event = new DomainEvent("order.confirmed") {};
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event, "order.confirmed");

        // Nothing sent while the transaction is still open
        verifyNoInteractions(rabbitTemplate);

        // Simulate the transaction commit
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.confirmed"),
            eq(event)
        );
    }

    @Test
    void should_deferDefaultRoutingKeyPublishUntilAfterCommit_when_transactionActive() {
        DomainEvent event = new DomainEvent("order.created") {};
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event);

        verifyNoInteractions(rabbitTemplate);

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(rabbitTemplate).convertAndSend(
            eq("order.exchange"),
            eq("order.created"),
            eq(event)
        );
    }

    @Test
    void should_notPublish_when_transactionRollsBack() {
        DomainEvent event = new DomainEvent("order.confirmed") {};
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event, "order.confirmed");

        // Rollback: synchronizations discarded without afterCommit
        TransactionSynchronizationManager.clearSynchronization();

        verifyNoInteractions(rabbitTemplate);
    }
}
