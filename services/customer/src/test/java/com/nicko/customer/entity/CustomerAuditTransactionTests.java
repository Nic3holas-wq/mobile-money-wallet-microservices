package com.nicko.customer.entity;

import com.nicko.customer.mapper.CustomerAuditMapper;
import com.nicko.customer.mapper.OutboxEventMapper;
import com.nicko.customer.repository.CustomerAuditRepository;
import com.nicko.customer.repository.OutboxEventRepository;
import com.nicko.customer.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerAuditTransactionTests {
    @Test void auditAndOutboxUseSameTransactionCorrelationAndKeepAllowlistedData() throws Exception {
        EntityManager entityManager = mock(EntityManager.class);
        CustomerAuditService audit = new CustomerAuditService(entityManager, mock(CustomerAuditMapper.class),
                mock(CustomerAuditRepository.class), mock(CustomerOwnership.class));
        OutboxEventRepository events = mock(OutboxEventRepository.class);
        when(events.save(any())).thenAnswer(invocation -> { OutboxEvent event = invocation.getArgument(0); event.setId(UUID.randomUUID()); return event; });
        OutboxEventService outbox = new OutboxEventService(events, mock(OutboxEventMapper.class), mock(CustomerOwnership.class));
        UUID customerId = UUID.randomUUID(), actor = UUID.randomUUID(), target = UUID.randomUUID();
        Customer customer = new Customer(); customer.setId(customerId);
        UUID correlationId = UUID.randomUUID();
        MDC.put("requestId", correlationId.toString());
        try {
            audit.record(customerId, actor, "KYC_UPDATED", "CUSTOMER", target, Map.of("tier", "0"), Map.of("tier", "1"));
            outbox.record(customer, "customer.kyc.changed.v1", "CUSTOMER", target, Map.of("tier", "1"));
        } finally {
            MDC.remove("requestId");
        }
        ArgumentCaptor<Object> auditCaptor = ArgumentCaptor.forClass(Object.class);
        verify(entityManager).persist(auditCaptor.capture());
        CustomerAuditRecord record = (CustomerAuditRecord) auditCaptor.getValue();
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(events).save(outboxCaptor.capture());
        assertEquals(correlationId, record.getCorrelationId());
        assertEquals(record.getCorrelationId(), outboxCaptor.getValue().getCorrelationId());
        assertEquals("1", record.getAfterState().get("tier"));
        assertEquals("customer.kyc.changed.v1", outboxCaptor.getValue().getEventType());
        assertEquals(Propagation.MANDATORY, CustomerAuditService.class.getMethod("record", UUID.class, UUID.class,
                String.class, String.class, UUID.class, Map.class, Map.class).getAnnotation(Transactional.class).propagation());
    }
}
