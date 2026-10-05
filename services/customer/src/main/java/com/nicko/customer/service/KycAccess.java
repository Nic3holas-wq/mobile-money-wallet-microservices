package com.nicko.customer.service;

import com.nicko.customer.entity.KycProfile;
import com.nicko.customer.entity.enums.KycStatus;
import com.nicko.customer.repository.KycProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class KycAccess {
    private final KycProfileRepository profiles;

    public KycProfile require(UUID customerId) {
        return profiles.findByCustomerId(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "KYC profile not found"));
    }
    public void requireEditable(KycProfile profile) {
        if (profile.getStatus() != KycStatus.NOT_STARTED && profile.getStatus() != KycStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC can only be edited before submission or after rejection");
        }
    }
    public void requireSubmitted(KycProfile profile) {
        if (profile.getStatus() != KycStatus.PENDING && profile.getStatus() != KycStatus.UNDER_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC must be submitted before review");
        }
    }
}
