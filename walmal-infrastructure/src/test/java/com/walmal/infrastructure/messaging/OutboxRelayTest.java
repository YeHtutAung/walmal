package com.walmal.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @InjectMocks private OutboxRelay relay;

    private OutboxEventRow row(UUID id, int attempts) {
        return new OutboxEventRow(id, "order.exchange", "order.confirmed",
                "{\"eventType\":\"order.confirmed\"}", attempts);
    }

    @Test
    void should_sendAndDeleteInOrder_when_pendingRowsExist() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id1, 0), row(id2, 0)));

        relay.relayPendingEvents();

        InOrder inOrder = inOrder(rabbitTemplate, outboxRepository);
        inOrder.verify(rabbitTemplate).send(eq("order.exchange"), eq("order.confirmed"), any(Message.class));
        inOrder.verify(outboxRepository).delete(id1);
        inOrder.verify(rabbitTemplate).send(eq("order.exchange"), eq("order.confirmed"), any(Message.class));
        inOrder.verify(outboxRepository).delete(id2);
        verify(outboxRepository, never()).recordFailure(any(), anyInt(), any(), anyBoolean());
    }

    @Test
    void should_sendPayloadAsJsonBytes() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id, 0)));

        relay.relayPendingEvents();

        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(any(), any(), captor.capture());
        Message sent = captor.getValue();
        assertThat(new String(sent.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventType\":\"order.confirmed\"}");
        assertThat(sent.getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    }

    @Test
    void should_recordFailureAndHaltBatch_when_sendFails() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id1, 0), row(id2, 0)));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxRepository).recordFailure(eq(id1), eq(1), contains("broker down"), eq(false));
        verify(outboxRepository, never()).delete(any());
        // Batch halted: second row never attempted (ordering preserved)
        verify(rabbitTemplate, times(1)).send(any(), any(), any(Message.class));
    }

    @Test
    void should_parkRowAsFailed_when_attemptsReachCap() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of(row(id, 59)));
        doThrow(new AmqpException("still down"))
                .when(rabbitTemplate).send(any(), any(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxRepository).recordFailure(eq(id), eq(60), contains("still down"), eq(true));
    }

    @Test
    void should_doNothing_when_noPendingRows() {
        when(outboxRepository.lockPendingBatch(anyInt())).thenReturn(List.of());

        relay.relayPendingEvents();

        verifyNoInteractions(rabbitTemplate);
        verify(outboxRepository, never()).delete(any());
    }
}
