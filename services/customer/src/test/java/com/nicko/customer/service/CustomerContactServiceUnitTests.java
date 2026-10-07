package com.nicko.customer.service;

import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerContact;
import com.nicko.customer.entity.enums.ContactType;
import com.nicko.customer.mapper.CustomerContactMapper;
import com.nicko.customer.repository.ContactVerificationRepository;
import com.nicko.customer.repository.CustomerContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerContactServiceUnitTests {
    @Test void createsPrimaryContactAndRecordsAuditAndOutbox() {
        CustomerContactRepository repository = mock(CustomerContactRepository.class);
        CustomerContactMapper mapper = new CustomerContactMapper(new ContactNormalizer());
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        ContactVerificationRepository challenges = mock(ContactVerificationRepository.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        OutboxEventService outbox = mock(OutboxEventService.class);
        CustomerContactService service = new CustomerContactService(repository, mapper, ownership, challenges, audit, outbox, Clock.systemUTC());
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lock(user)).thenReturn(customer);
        when(repository.existsByContactTypeAndContactValue(ContactType.EMAIL, "user@example.com")).thenReturn(false);
        when(repository.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), ContactType.EMAIL)).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> { CustomerContact contact = invocation.getArgument(0); contact.setId(UUID.randomUUID()); return contact; });
        service.create(user, new CustomerContactRequest(ContactType.EMAIL, " USER@example.com ", true, null));
        verify(repository).saveAndFlush(argThat(c -> c.getContactValue().equals("user@example.com") && c.isPrimary()));
        verify(audit).record(eq(customer.getId()), eq(user), eq("CONTACT_CREATED"), eq("CUSTOMER_CONTACT"), any(), anyMap(), anyMap());
        verify(outbox).record(eq(customer), eq("customer.contact.primary.changed.v1"), eq("CUSTOMER_CONTACT"), any(), anyMap());
    }

    @Test void duplicateContactIsConflictAndPageBoundsAreValidated() {
        CustomerContactRepository repository = mock(CustomerContactRepository.class);
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerContactService service = new CustomerContactService(repository,
                new CustomerContactMapper(new ContactNormalizer()), ownership,
                mock(ContactVerificationRepository.class), mock(CustomerAuditService.class), mock(OutboxEventService.class), Clock.systemUTC());
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lock(user)).thenReturn(customer);
        when(repository.existsByContactTypeAndContactValue(ContactType.EMAIL, "user@example.com")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.create(user, new CustomerContactRequest(ContactType.EMAIL, "user@example.com", false, null))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.list(user, 0, 101)).getStatusCode());
    }
}
