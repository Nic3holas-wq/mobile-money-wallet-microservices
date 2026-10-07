package com.nicko.customer.service;

import com.nicko.customer.dto.CustomerConsentRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.CustomerConsent;
import com.nicko.customer.entity.enums.ConsentType;
import com.nicko.customer.mapper.CustomerConsentMapper;
import com.nicko.customer.repository.CustomerConsentRepository;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerConsentServiceUnitTests {
    @Test void acceptanceNormalizesVersionAndPersistsAcceptanceMetadata() throws Exception {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerConsentRepository repository = mock(CustomerConsentRepository.class);
        CustomerConsentMapper mapper = mock(CustomerConsentMapper.class);
        ConsentPolicyService policies = mock(ConsentPolicyService.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        CustomerConsentService service = new CustomerConsentService(ownership, repository, mapper, policies, audit, clock);
        UUID user = UUID.randomUUID(); Customer customer = new Customer(); customer.setId(UUID.randomUUID());
        when(ownership.lock(user)).thenReturn(customer);
        when(repository.existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                customer.getId(), ConsentType.TERMS_AND_CONDITIONS, "v1")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> { CustomerConsent c = invocation.getArgument(0); c.setId(UUID.randomUUID()); return c; });
        service.accept(user, new CustomerConsentRequest(ConsentType.TERMS_AND_CONDITIONS, " v1 "),
                java.net.InetAddress.getLoopbackAddress(), "unit-test");
        verify(policies).validate(ConsentType.TERMS_AND_CONDITIONS, "v1");
        verify(repository).saveAndFlush(argThat(c -> c.isAccepted() && c.getChannel().equals("API")
                && c.getAcceptedAt().equals(clock.instant()) && c.getUserAgent().equals("unit-test")));
        verify(audit).record(eq(customer.getId()), eq(user), eq("CONSENT_ACCEPTED"), eq("CUSTOMER_CONSENT"), any(), anyMap(), anyMap());
    }

    @Test void withdrawingMandatoryConsentDisablesWalletAndRepeatingWithdrawalDoesNotRepeatAudit() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerConsentRepository repository = mock(CustomerConsentRepository.class);
        CustomerAuditService audit = mock(CustomerAuditService.class);
        Customer customer = new Customer(); customer.setId(UUID.randomUUID()); customer.setWalletEligible(true);
        CustomerConsent consent = new CustomerConsent(); consent.setId(UUID.randomUUID()); consent.setCustomer(customer);
        consent.setConsentType(ConsentType.PRIVACY_POLICY); consent.setDocumentVersion("v1"); consent.setAccepted(true);
        UUID user = UUID.randomUUID(), id = consent.getId();
        when(ownership.lock(user)).thenReturn(customer);
        when(repository.findByIdAndCustomerId(id, customer.getId())).thenReturn(Optional.of(consent));
        when(repository.saveAndFlush(consent)).thenReturn(consent);
        CustomerConsentService service = new CustomerConsentService(ownership, repository, mock(CustomerConsentMapper.class),
                mock(ConsentPolicyService.class), audit, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        service.withdraw(user, id);
        assertFalse(customer.isWalletEligible());
        assertNotNull(consent.getWithdrawnAt());
        service.withdraw(user, id);
        verify(audit, times(1)).record(eq(customer.getId()), eq(user), eq("CONSENT_WITHDRAWN"), anyString(), eq(id), anyMap(), anyMap());
    }
}
