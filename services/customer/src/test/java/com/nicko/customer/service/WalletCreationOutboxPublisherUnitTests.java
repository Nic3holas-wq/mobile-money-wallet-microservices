package com.nicko.customer.service;

import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.OutboxEvent;
import com.nicko.customer.entity.enums.CustomerStatus;
import com.nicko.customer.entity.enums.OutboxStatus;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WalletCreationOutboxPublisherUnitTests {
    @Test void backfillsMissingEventsOnceAndPublishesPendingEvents() {
        OutboxEventRepository events = mock(OutboxEventRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        OutboxEventService recorder = mock(OutboxEventService.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        WalletCreationOutboxPublisher publisher = new WalletCreationOutboxPublisher(events, customers, recorder, kafka);
        ReflectionTestUtils.setField(publisher, "topic", "wallet-create");
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(customers.findAllByCustomerStatusAndWalletEligibleTrue(CustomerStatus.ACTIVE)).thenReturn(List.of(customer));
        when(events.existsByEventTypeAndAggregateId("customer.wallet.creation.requested.v1", customer.getId())).thenReturn(false);
        OutboxEvent event = new OutboxEvent(); event.setAggregateId(customer.getId()); event.setPayload(java.util.Map.of("currency", "KES"));
        when(events.findTop100ByEventTypeAndStatusOrderByCreatedAtAsc("customer.wallet.creation.requested.v1", OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPendingWalletCreationRequests();
        verify(recorder).record(customer, "customer.wallet.creation.requested.v1", "CUSTOMER", customer.getId(), java.util.Map.of("currency", "KES"));
        verify(kafka).send("wallet-create", customer.getId().toString(), event.getPayload());
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        publisher.publishPendingWalletCreationRequests();
        verify(customers, times(1)).findAllByCustomerStatusAndWalletEligibleTrue(CustomerStatus.ACTIVE);
    }

    @Test void recordsFailureWithoutMarkingEventPublished() {
        OutboxEventRepository events = mock(OutboxEventRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        WalletCreationOutboxPublisher publisher = new WalletCreationOutboxPublisher(events, customers, mock(OutboxEventService.class), kafka);
        ReflectionTestUtils.setField(publisher, "topic", "wallet-create");
        OutboxEvent event = new OutboxEvent(); event.setAggregateId(UUID.randomUUID());
        when(customers.findAllByCustomerStatusAndWalletEligibleTrue(CustomerStatus.ACTIVE)).thenReturn(List.of());
        when(events.findTop100ByEventTypeAndStatusOrderByCreatedAtAsc(anyString(), eq(OutboxStatus.PENDING))).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        publisher.publishPendingWalletCreationRequests();
        assertEquals(1, event.getAttemptCount());
        assertNotNull(event.getLastError());
        assertNotEquals(OutboxStatus.PUBLISHED, event.getStatus());
    }
}
