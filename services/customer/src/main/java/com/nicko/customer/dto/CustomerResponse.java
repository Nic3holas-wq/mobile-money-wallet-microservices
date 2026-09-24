package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.CustomerStatus;
import com.nicko.customer.customer.enums.KycStatus;
import com.nicko.customer.customer.enums.KycTier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerResponse(UUID id, String customerNumber, String firstName,
        String middleName, String lastName, LocalDate dateOfBirth, String gender,
        String nationality, String preferredLanguage, CustomerStatus customerStatus,
        KycStatus kycStatus, KycTier kycTier, boolean walletEligible, Instant createdAt) {
}
