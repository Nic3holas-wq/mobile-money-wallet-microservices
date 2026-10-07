package com.nicko.customer.service;

import com.nicko.customer.dto.ConfirmContactRequest;
import com.nicko.customer.entity.ContactVerificationChallenge;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerContact;
import com.nicko.customer.entity.enums.ContactType;
import com.nicko.customer.repository.ContactVerificationRepository;
import com.nicko.customer.repository.CustomerContactRepository;
import com.nicko.customer.mapper.CustomerContactMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContactVerificationServiceUnitTests {
    @Test void requestAndConfirmCodeVerifyContactAndEmitAuditAndOutbox() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerContactRepository contacts = mock(CustomerContactRepository.class);
        ContactVerificationRepository challenges = mock(ContactVerificationRepository.class);
        VerificationCodeProtection protection = new VerificationCodeProtection(Base64.getEncoder().encodeToString(new byte[32]));
        VerificationDelivery delivery = mock(VerificationDelivery.class);
        CustomerContactMapper mapper = mock(CustomerContactMapper.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        OutboxEventService outbox = mock(OutboxEventService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        ContactVerificationService service = new ContactVerificationService(ownership, contacts, challenges,
                protection, delivery, mapper, audit, outbox, clock);
        UUID user = UUID.randomUUID(), contactId = UUID.randomUUID();
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        CustomerContact contact = new CustomerContact(); contact.setId(contactId); contact.setCustomer(customer);
        contact.setContactType(ContactType.EMAIL); contact.setContactValue("user@example.com");
        when(ownership.lock(user)).thenReturn(customer);
        when(contacts.findByIdAndCustomerId(contactId, customer.getId())).thenReturn(Optional.of(contact));
        when(challenges.findFirstByContactIdOrderByCreatedAtDesc(contactId)).thenReturn(Optional.empty());
        when(challenges.countByCustomerIdAndCreatedAtAfter(eq(customer.getId()), any())).thenReturn(0L);
        when(challenges.findByContactIdAndConsumedAtIsNull(contactId)).thenReturn(List.of());
        when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.request(user, contactId);
        ArgumentCaptor<ContactVerificationChallenge> saved = ArgumentCaptor.forClass(ContactVerificationChallenge.class);
        verify(challenges).saveAndFlush(saved.capture());
        ContactVerificationChallenge challenge = saved.getValue();
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(delivery).send(eq(challenge.getId()), eq(ContactType.EMAIL), eq("user@example.com"), codeCaptor.capture());
        when(challenges.findByIdAndCustomerIdAndContactId(challenge.getId(), customer.getId(), contactId)).thenReturn(Optional.of(challenge));
        service.confirm(user, contactId, challenge.getId(), new ConfirmContactRequest(codeCaptor.getValue()));
        assertTrue(contact.isVerified());
        assertEquals(com.nicko.customer.entity.enums.VerificationSource.OTP, contact.getVerificationSource());
        verify(audit).record(eq(customer.getId()), eq(user), eq("CONTACT_VERIFIED"), eq("CUSTOMER_CONTACT"), eq(contactId), anyMap(), anyMap());
        verify(outbox).record(eq(customer), eq("customer.contact.verified.v1"), eq("CUSTOMER_CONTACT"), eq(contactId), anyMap());
    }

    @Test void rateLimitsChallengeResends() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerContactRepository contacts = mock(CustomerContactRepository.class);
        ContactVerificationRepository challenges = mock(ContactVerificationRepository.class);
        VerificationDelivery delivery = mock(VerificationDelivery.class);
        ContactVerificationService service = new ContactVerificationService(ownership, contacts, challenges,
                new VerificationCodeProtection(Base64.getEncoder().encodeToString(new byte[32])), delivery,
                mock(CustomerContactMapper.class), mock(CustomerAuditService.class), mock(OutboxEventService.class),
                Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
        UUID user = UUID.randomUUID(), contactId = UUID.randomUUID();
        Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        CustomerContact contact = new CustomerContact(); contact.setId(contactId); contact.setContactType(ContactType.PHONE);
        when(ownership.lock(user)).thenReturn(customer);
        when(contacts.findByIdAndCustomerId(contactId, customer.getId())).thenReturn(Optional.of(contact));
        ContactVerificationChallenge recent = new ContactVerificationChallenge(); recent.setCreatedAt(Instant.parse("2026-10-06T23:59:30Z"));
        when(challenges.findFirstByContactIdOrderByCreatedAtDesc(contactId)).thenReturn(Optional.of(recent));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                () -> service.request(user, contactId)).getStatusCode());
        verify(delivery, never()).send(any(), any(), anyString(), anyString());
    }
}
