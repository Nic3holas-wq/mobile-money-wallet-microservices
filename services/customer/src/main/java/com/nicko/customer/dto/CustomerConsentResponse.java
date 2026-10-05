package com.nicko.customer.dto;

import com.nicko.customer.entity.enums.*;
import java.time.Instant;
import java.util.UUID;

public record CustomerConsentResponse(UUID id, ConsentType consentType, String documentVersion, String channel, boolean accepted, Instant acceptedAt, Instant withdrawnAt, Instant createdAt) {}
