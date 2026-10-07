package com.nicko.customer.service;

import com.nicko.customer.dto.CustomerContactRequest;
import com.nicko.customer.entity.Customer;
import com.nicko.customer.entity.KycDocument;
import com.nicko.customer.entity.KycProfile;
import com.nicko.customer.entity.CustomerLimit;
import com.nicko.customer.entity.enums.*;
import com.nicko.customer.repository.CustomerConsentRepository;
import com.nicko.customer.repository.CustomerRepository;
import com.nicko.customer.repository.KycProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerPolicyUnitTests {
    @Test void apiPagesEnforcesBoundsAndDeterministicSort() {
        assertEquals(2, ApiPages.of(2, 100).getPageNumber());
        assertEquals("createdAt: ASC,id: ASC", ApiPages.of(0, 10).getSort().toString());
        assertThrows(ResponseStatusException.class, () -> ApiPages.of(-1, 10));
        assertThrows(ResponseStatusException.class, () -> ApiPages.of(0, 0));
        assertThrows(ResponseStatusException.class, () -> ApiPages.of(0, 101));
    }

    @Test void onboardingPolicyUsesClockAndAgeBoundary() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        OnboardingPolicy policy = new OnboardingPolicy(clock, 18);
        assertTrue(policy.oldEnough(LocalDate.of(2008, 10, 7)));
        assertFalse(policy.oldEnough(LocalDate.of(2008, 10, 8)));
        assertFalse(policy.oldEnough(null));
        assertThrows(FieldValidationException.class, () -> policy.validate(null));
        assertThrows(IllegalArgumentException.class, () -> new OnboardingPolicy(clock, 121));
    }

    @Test void contactNormalizerCanonicalizesEmailAndPhoneAndRejectsInvalidNumbers() {
        ContactNormalizer normalizer = new ContactNormalizer();
        assertEquals("user@example.com", normalizer.normalize(new CustomerContactRequest(ContactType.EMAIL, " User@Example.COM ", false, null)));
        assertEquals("+14155552671", normalizer.normalize(new CustomerContactRequest(ContactType.PHONE, "(415) 555-2671", false, "US")));
        assertThrows(FieldValidationException.class, () -> normalizer.normalize(new CustomerContactRequest(ContactType.PHONE, "1234", false, null)));
        assertThrows(FieldValidationException.class, () -> normalizer.normalize(new CustomerContactRequest(ContactType.PHONE, "1234", false, "US")));
    }

    @Test void consentPolicyValidatesVersionsAndComputesRequiredAcceptance() {
        CustomerConsentRepository repository = mock(CustomerConsentRepository.class);
        UUID customerId = UUID.randomUUID();
        ConsentPolicyService policy = new ConsentPolicyService(repository, "terms-3", "privacy-2", "marketing-1");
        policy.validate(ConsentType.TERMS_AND_CONDITIONS, "terms-3");
        assertThrows(FieldValidationException.class, () -> policy.validate(ConsentType.TERMS_AND_CONDITIONS, "old"));
        when(repository.existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                eq(customerId), any(), anyString())).thenReturn(true, false, true);
        assertFalse(policy.mandatorySatisfied(customerId));
        assertEquals(3, policy.current(customerId).size());
        assertThrows(IllegalArgumentException.class, () -> new ConsentPolicyService(repository, " ", "p", "m"));
    }

    @Test void verificationCodeProtectionUsesHmacAndRejectsMissingKey() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        VerificationCodeProtection protection = new VerificationCodeProtection(key);
        String one = protection.hash("challenge:123456");
        assertEquals(64, one.length());
        assertEquals(one, protection.hash("challenge:123456"));
        assertNotEquals(one, protection.hash("challenge:654321"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new VerificationCodeProtection("").hash("x"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test void ownershipMethodsReturnMatchingCustomerAndUseNotFoundForMissingRecords() {
        CustomerRepository repository = mock(CustomerRepository.class);
        CustomerOwnership ownership = new CustomerOwnership(repository);
        UUID user = UUID.randomUUID(), id = UUID.randomUUID();
        Customer customer = new Customer();
        when(repository.findByKeycloakUserId(user)).thenReturn(Optional.of(customer));
        when(repository.findById(id)).thenReturn(Optional.of(customer));
        when(repository.findForUpdateById(id)).thenReturn(Optional.of(customer));
        when(repository.findForUpdateByUserId(user)).thenReturn(Optional.of(customer));
        assertSame(customer, ownership.require(user));
        assertSame(customer, ownership.requireById(id));
        assertSame(customer, ownership.lockById(id));
        assertSame(customer, ownership.lock(user));
        when(repository.findByKeycloakUserId(user)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> ownership.require(user)).getStatusCode());
    }

    @Test void kycAccessEnforcesEditableAndSubmittedStates() {
        KycProfileRepository repository = mock(KycProfileRepository.class);
        KycAccess access = new KycAccess(repository);
        UUID id = UUID.randomUUID();
        KycProfile profile = new KycProfile();
        profile.setStatus(KycStatus.NOT_STARTED);
        when(repository.findByCustomerId(id)).thenReturn(Optional.of(profile));
        assertSame(profile, access.require(id));
        access.requireEditable(profile);
        profile.setStatus(KycStatus.REJECTED);
        access.requireEditable(profile);
        profile.setStatus(KycStatus.PENDING);
        access.requireSubmitted(profile);
        profile.setStatus(KycStatus.APPROVED);
        assertThrows(ResponseStatusException.class, () -> access.requireEditable(profile));
        assertThrows(ResponseStatusException.class, () -> access.requireSubmitted(profile));
    }

    @Test void auditSnapshotsAllowlistSensitiveEntityFields() {
        KycProfile profile = new KycProfile();
        profile.setStatus(KycStatus.PENDING);
        profile.setRejectionReason("retry");
        assertTrue(AuditSnapshots.profile(profile).containsKey("status"));
        assertFalse(AuditSnapshots.profile(profile).containsKey("documentNumber"));
        CustomerLimit limit = new CustomerLimit();
        limit.setCurrency("KES");
        assertEquals("KES", AuditSnapshots.limit(limit).get("currency"));
        KycDocument document = new KycDocument();
        KycProfile owner = new KycProfile();
        owner.setId(UUID.randomUUID());
        document.setKycProfile(owner);
        document.setId(UUID.randomUUID());
        document.setDocumentNumberHash("secret-hash");
        document.setDocumentNumberEncrypted("encrypted-number");
        assertFalse(AuditSnapshots.document(document).containsValue("secret-hash"));
        assertFalse(AuditSnapshots.document(document).containsValue("encrypted-number"));
    }
}
