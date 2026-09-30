package com.nicko.customer.service;

import com.nicko.customer.customer.Customer;
import com.nicko.customer.dto.CustomerCompletionResponse;
import com.nicko.customer.customer.enums.*;
import com.nicko.customer.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerCompletionService {
    private final CustomerOwnership ownership;
    private final CustomerContactRepository contacts;
    private final KycProfileRepository profiles;
    private final KycDocumentRepository documents;
    private final ConsentPolicyService consent;
    private final Clock clock;
    private final OnboardingPolicy policy;
    @Transactional(readOnly = true)
    public CustomerCompletionResponse get(UUID userId) {
        var missing = outstandingSteps(ownership.require(userId));
        // Completion guides onboarding; it does not activate the customer or authorize a wallet.
        return new CustomerCompletionResponse(missing.isEmpty(), missing);
    }

    // Shared with staff activation so both apply the same onboarding requirements.
    java.util.List<String> outstandingSteps(Customer customer) {
        var missing = new ArrayList<String>();
        if (!policy.oldEnough(customer.getDateOfBirth())) { missing.add("MINIMUM_AGE_NOT_MET"); }
        if (contacts.findByCustomerIdAndContactTypeAndPrimaryTrue(customer.getId(), ContactType.PHONE)
                .stream().noneMatch(c -> c.isVerified())) { missing.add("VERIFY_PRIMARY_PHONE"); }
        if (!consent.mandatorySatisfied(customer.getId())) { missing.add("ACCEPT_REQUIRED_POLICIES"); }
        var profile = profiles.findByCustomerId(customer.getId()).orElse(null);
        boolean validKyc = profile != null && profile.getStatus() == KycStatus.APPROVED
                && profile.getExpiresAt() != null && profile.getExpiresAt().isAfter(clock.instant());
        if (validKyc) {
            var evidence = documents.findByKycProfileId(profile.getId());
            validKyc = !evidence.isEmpty() && evidence.stream().allMatch(d -> d.getVerificationStatus() == DocumentVerificationStatus.VERIFIED
                    && (d.getExpiresAt() == null || d.getExpiresAt().isAfter(LocalDate.now(clock))));
        }
        if (!validKyc) { missing.add("COMPLETE_KYC"); }
        return java.util.List.copyOf(missing);
    }
}
