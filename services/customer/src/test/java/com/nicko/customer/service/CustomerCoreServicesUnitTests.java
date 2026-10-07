package com.nicko.customer.service;

import com.nicko.customer.dto.RegisterCustomerRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerAuditRecord;
import com.nicko.customer.entity.OutboxEvent;
import com.nicko.customer.entity.enums.*;
import com.nicko.customer.mapper.CustomerMapper;
import com.nicko.customer.mapper.CustomerAuditMapper;
import com.nicko.customer.mapper.OutboxEventMapper;
import com.nicko.customer.repository.CustomerAuditRepository;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.repository.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerCoreServicesUnitTests {
    @Test void customerRegistrationAssignsPublicNumberAndEmitsAuditAndOutbox() {
        CustomerRepository repository = mock(CustomerRepository.class);
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        OutboxEventService outbox = mock(OutboxEventService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        OnboardingPolicy age = new OnboardingPolicy(clock, 18);
        CustomerService service = new CustomerService(repository, mapper, mock(CustomerOwnership.class), audit,
                outbox, clock, jdbc, age, mock(CustomerCompletionService.class));
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(repository.existsByKeycloakUserId(user)).thenReturn(false);
        when(mapper.toEntity(any())).thenReturn(customer);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
        when(repository.saveAndFlush(customer)).thenReturn(customer);
        service.register(user, new RegisterCustomerRequest("A", null, "B", LocalDate.of(1990, 1, 1), null, "KE", "en"));
        assertEquals("CUS-2026-000042", customer.getCustomerNumber());
        assertEquals(CustomerStatus.PENDING, customer.getCustomerStatus());
        assertFalse(customer.isWalletEligible());
        verify(audit).record(eq(customer.getId()), eq(user), eq("CUSTOMER_REGISTERED"), eq("CUSTOMER"), eq(customer.getId()), anyMap(), anyMap());
        verify(outbox).record(eq(customer), eq("customer.registered.v1"), eq("CUSTOMER"), eq(customer.getId()), anyMap());
    }

    @Test void auditRecordPersistsOnlyTheExplicitSnapshotAndOutboxRecordHasEnvelope() {
        EntityManager entityManager = mock(EntityManager.class);
        CustomerAuditService audit = new CustomerAuditService(entityManager, mock(CustomerAuditMapper.class),
                mock(CustomerAuditRepository.class), mock(CustomerOwnership.class));
        UUID customerId = UUID.randomUUID(), actor = UUID.randomUUID(), target = UUID.randomUUID();
        audit.record(customerId, actor, "UPDATED", "CUSTOMER", target, Map.of("name", "old"), Map.of("name", "new"));
        verify(entityManager).persist(argThat(value -> {
            CustomerAuditRecord record = (CustomerAuditRecord) value;
            return record.getCustomerId().equals(customerId) && record.getActorId().equals(actor)
                    && record.getAction().equals("UPDATED") && record.getBeforeState().get("name").equals("old");
        }));

        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        Customer customer = new Customer(); customer.setId(customerId); customer.setKycStatus(KycStatus.PENDING);
        when(repository.save(any())).thenAnswer(invocation -> { OutboxEvent event = invocation.getArgument(0); event.setId(UUID.randomUUID()); return event; });
        OutboxEventService outbox = new OutboxEventService(repository, mock(OutboxEventMapper.class), mock(CustomerOwnership.class));
        outbox.record(customer, "test.v1", "CUSTOMER", target, Map.of("field", "value"));
        verify(repository).save(argThat(event -> event.getStatus() == OutboxStatus.PENDING
                && event.getPayload().get("eventType").equals("test.v1")
                && event.getPayload().get("customerId").equals(customerId.toString())
                && event.getPayload().get("data") instanceof Map));
    }
}
