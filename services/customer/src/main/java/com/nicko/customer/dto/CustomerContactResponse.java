package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.ContactType;
import com.nicko.customer.entity.enums.VerificationSource;
import java.time.Instant;
import java.util.UUID;

public record CustomerContactResponse(UUID id, ContactType contactType, String contactValue,
        boolean primary, boolean verified, Instant verifiedAt, VerificationSource verificationSource,
        Instant createdAt, Instant updatedAt) {}
