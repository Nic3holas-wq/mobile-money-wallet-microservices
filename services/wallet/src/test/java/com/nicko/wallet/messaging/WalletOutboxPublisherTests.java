package com.nicko.wallet.messaging;

import com.nicko.wallet.entity.OutboxEvent;
import com.nicko.wallet.entity.enums.OutboxStatus;
import com.nicko.wallet.event.WalletEventTypes;
import com.nicko.wallet.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletOutboxPublisherTests {
    @Mock OutboxEventRepository repository;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    WalletOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new WalletOutboxPublisher(repository, kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "walletCreatedTopic", "wallet-created");
        ReflectionTestUtils.setField(publisher, "transferCompletedTopic", "transfer-completed");
        ReflectionTestUtils.setField(publisher, "transferFailedTopic", "transfer-failed");
    }

    @Test
    void publishesSupportedTypesToTheirTopicsAndMarksPublished() {
        OutboxEvent created = event(WalletEventTypes.WALLET_CREATED);
        OutboxEvent completed = event(WalletEventTypes.WALLET_TRANSFER_COMPLETED);
        OutboxEvent failed = event(WalletEventTypes.WALLET_TRANSFER_FAILED);
        when(repository.findTop100ByStatusAndEventTypeInOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), anyList()))
                .thenReturn(List.of(created, completed, failed));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingEvents();

        verify(kafkaTemplate).send(eq("wallet-created"), eq(created.getAggregateId().toString()), eq(created.getPayload()));
        verify(kafkaTemplate).send(eq("transfer-completed"), eq(completed.getAggregateId().toString()), eq(completed.getPayload()));
        verify(kafkaTemplate).send(eq("transfer-failed"), eq(failed.getAggregateId().toString()), eq(failed.getPayload()));
        for (OutboxEvent event : List.of(created, completed, failed)) {
            assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
            assertNotNull(event.getPublishedAt());
            assertNull(event.getLastError());
        }
    }

    @Test
    void recordsSendFailureAndLeavesEventPendingForRetry() {
        OutboxEvent event = event(WalletEventTypes.WALLET_CREATED);
        event.setAttemptCount(2);
        when(repository.findTop100ByStatusAndEventTypeInOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), anyList()))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        publisher.publishPendingEvents();

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(3, event.getAttemptCount());
        assertNotNull(event.getLastError());
    }

    @Test
    void skipsUnrecognizedType() {
        OutboxEvent event = event("UNEXPECTED");
        when(repository.findTop100ByStatusAndEventTypeInOrderByCreatedAtAsc(eq(OutboxStatus.PENDING), anyList()))
                .thenReturn(List.of(event));

        publisher.publishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
        assertEquals(OutboxStatus.PENDING, event.getStatus());
    }

    private static OutboxEvent event(String type) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(UUID.randomUUID());
        event.setEventType(type);
        event.setPayload(java.util.Map.of("id", "value"));
        event.setStatus(OutboxStatus.PENDING);
        return event;
    }
}
