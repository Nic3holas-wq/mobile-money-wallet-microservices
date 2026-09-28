package com.nicko.customer.dto;

import com.nicko.customer.customer.enums.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record KycProfileResponse(UUID id, KycStatus status, KycTier requestedTier, KycTier approvedTier, String occupation, String employerName, SourceOfFunds sourceOfFunds, BigDecimal expectedMonthlyVolume, Instant submittedAt, Instant reviewedAt, String rejectionReason, Instant expiresAt, Long version, Instant createdAt, Instant updatedAt) {}
