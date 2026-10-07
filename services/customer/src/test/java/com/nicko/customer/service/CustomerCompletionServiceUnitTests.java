package com.nicko.customer.service;

import com.nicko.customer.entity.*;
import com.nicko.customer.entity.enums.*;
import com.nicko.customer.repository.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerCompletionServiceUnitTests {
    @Test void completionReportsMissingRequirementsAndCanReachComplete() {
        CustomerOwnership ownership = mock(CustomerOwnership.class);
        CustomerContactRepository contacts = mock(CustomerContactRepository.class);
        KycProfileRepository profiles = mock(KycProfileRepository.class);
        KycDocumentRepository documents = mock(KycDocumentRepository.class);
        ConsentPolicyService consent = mock(ConsentPolicyService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        CustomerCompletionService service = new CustomerCompletionService(ownership, contacts, profiles, documents,
                consent, clock, new OnboardingPolicy(clock, 18));
        UUID user = UUID.randomUUID();
        Customer customer = new Customer(); customer.setId(UUID.randomUUID()); customer.setDateOfBirth(LocalDate.of(1990, 1, 1));
        when(ownership.require(user)).thenReturn(customer);
        when(contacts.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), ContactType.PHONE)).thenReturn(List.of());
        when(consent.mandatorySatisfied(customer.getId())).thenReturn(false);
        when(profiles.findByCustomerId(customer.getId())).thenReturn(Optional.empty());
        assertEquals(List.of("VERIFY_PRIMARY_PHONE", "ACCEPT_REQUIRED_POLICIES", "COMPLETE_KYC"), service.get(user).outstandingSteps());

        CustomerContact phone = new CustomerContact(); phone.setVerified(true);
        when(contacts.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), ContactType.PHONE)).thenReturn(List.of(phone));
        when(consent.mandatorySatisfied(customer.getId())).thenReturn(true);
        KycProfile profile = new KycProfile(); profile.setId(UUID.randomUUID()); profile.setStatus(KycStatus.APPROVED);
        profile.setExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        when(profiles.findByCustomerId(customer.getId())).thenReturn(Optional.of(profile));
        KycDocument document = new KycDocument(); document.setVerificationStatus(DocumentVerificationStatus.VERIFIED);
        when(documents.findByKycProfileId(profile.getId())).thenReturn(List.of(document));
        var response = service.get(user);
        assertTrue(response.complete());
        assertTrue(response.outstandingSteps().isEmpty());
    }
}
