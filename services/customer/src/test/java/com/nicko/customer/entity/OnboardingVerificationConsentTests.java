package com.nicko.customer.entity;

import com.nicko.customer.entity.enums.ConsentType;
import com.nicko.customer.repository.CustomerConsentRepository;
import com.nicko.customer.service.ConsentPolicyService;
import com.nicko.customer.service.FieldValidationException;
import com.nicko.customer.service.OnboardingPolicy;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnboardingVerificationConsentTests {
    @Test void agePolicyAppliesTheExactMinimumAgeBoundary() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        OnboardingPolicy policy = new OnboardingPolicy(clock, 18);
        assertTrue(policy.oldEnough(LocalDate.of(2008, 10, 7)));
        assertFalse(policy.oldEnough(LocalDate.of(2008, 10, 8)));
        policy.validate(LocalDate.of(2008, 10, 7));
        assertThrows(FieldValidationException.class, () -> policy.validate(LocalDate.of(2008, 10, 8)));
    }

    @Test void consentPolicyReadsCurrentAcceptedVersionsFromMockRepository() {
        CustomerConsentRepository repository = mock(CustomerConsentRepository.class);
        UUID customer = UUID.randomUUID();
        ConsentPolicyService policy = new ConsentPolicyService(repository, "terms-v2", "privacy-v4", "marketing-v1");
        when(repository.existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                eq(customer), any(), anyString())).thenReturn(true, false, false);
        var current = policy.current(customer);
        assertEquals(3, current.size());
        assertEquals("terms-v2", current.getFirst().requiredVersion());
        assertTrue(policy.mandatorySatisfied(customer) == false);
        policy.validate(ConsentType.TERMS_AND_CONDITIONS, "terms-v2");
        assertThrows(FieldValidationException.class, () -> policy.validate(ConsentType.PRIVACY_POLICY, "privacy-v3"));
        verify(repository, times(6)).existsByCustomerIdAndConsentTypeAndDocumentVersionAndAcceptedTrueAndWithdrawnAtIsNull(
                eq(customer), any(), anyString());
    }
}
